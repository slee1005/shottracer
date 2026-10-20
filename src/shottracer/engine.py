"""Project storage, analysis pipeline and edit application.

A project lives in WORKSPACE/<id>/:
    project.json      settings + analysis results
    source.<ext>      the video (or a reference to the original for CLI runs)
    previews/NNNNN.jpg  analysis-resolution frames for the UI
    shots/<key>/      one directory per traced shot (up to settings.MAX_TRACES):
        masks/NNNNN.png   golfer segmentation (tracer cut-out)
        transforms.npy    per-frame camera transforms (frame -> that shot's reference)
"""

from __future__ import annotations

import os
import re
import shutil
import threading
import uuid
from pathlib import Path

import cv2
import numpy as np

from . import ball, camera, putt, swing, trajectory, video
from .person import PersonFrame, PoseRunner
from .settings import MAX_TRACES, Project, Trace
from .trajectory import AnalysisError, Model

WORKSPACE = Path(os.environ.get("SHOTTRACER_HOME", Path.home() / ".shottracer" / "projects"))
WORK_WIDTH = 960
LONG_WORK_WIDTH = 640  # keeps memory bounded for long clips
LONG_CLIP_FRAMES = 900
# Only frames around the shot are analysed: a little before the address frame (swing
# start) up to the longest plausible flight plus margin.
BEFORE_SECONDS = 1.0
AFTER_SECONDS = 15.0

_locks: dict[str, threading.Lock] = {}


def lock(pid: str) -> threading.Lock:
    return _locks.setdefault(pid, threading.Lock())


def workdir(pid: str) -> Path:
    return WORKSPACE / pid


def tracedir(pid: str, tr: Trace) -> Path:
    return workdir(pid) / "shots" / tr.key


def source_path(p: Project) -> Path:
    src = Path(p.source)
    return src if src.is_absolute() else workdir(p.id) / src


def update_status(p: Project) -> None:
    """Project status summarises its traces: busy > ready > error > new."""
    states = [tr.status for tr in p.traces]
    for status in ("analysing", "ready", "error"):
        if status in states:
            p.status = status
            break
    else:
        p.status = "new"
    p.error = next((tr.error for tr in p.traces if tr.error), None)


def save(p: Project) -> None:
    update_status(p)
    d = workdir(p.id)
    d.mkdir(parents=True, exist_ok=True)
    tmp = d / "project.json.tmp"
    tmp.write_text(p.model_dump_json(indent=2))
    tmp.replace(d / "project.json")


def load(pid: str) -> Project:
    path = workdir(pid) / "project.json"
    if not path.exists():
        raise KeyError(pid)
    p = Project.model_validate_json(path.read_text())
    legacy = workdir(pid) / "transforms.npy"
    if legacy.exists():  # single-shot layout: move its files into the first trace
        dest = tracedir(pid, p.traces[0])
        dest.mkdir(parents=True, exist_ok=True)
        for name in ("transforms.npy", "masks"):
            if (workdir(pid) / name).exists() and not (dest / name).exists():
                shutil.move(str(workdir(pid) / name), str(dest / name))
    return p


def list_projects() -> list[Project]:
    if not WORKSPACE.exists():
        return []
    out = []
    for d in sorted(WORKSPACE.iterdir(), key=lambda d: d.stat().st_mtime):
        if (d / "project.json").exists():
            try:
                out.append(load(d.name))
            except Exception:
                continue
    return out


def delete(pid: str) -> None:
    shutil.rmtree(workdir(pid), ignore_errors=True)


def work_width_for(frame_count: int) -> int:
    return WORK_WIDTH if frame_count <= LONG_CLIP_FRAMES else LONG_WORK_WIDTH


def create(src: Path, name: str | None = None, copy: bool = True) -> Project:
    """Register a video. With copy=False (CLI) the original file is referenced in place."""
    pid = uuid.uuid4().hex[:10]
    d = workdir(pid)
    d.mkdir(parents=True)
    if copy:
        dest = d / f"source{src.suffix.lower() or '.mp4'}"
        shutil.copyfile(src, dest)
        source = dest.name
    else:
        dest = src.resolve()
        source = str(dest)
    info = video.probe(dest)
    ww = work_width_for(info.frame_count)
    count = video.write_previews(dest, d / "previews", ww)
    aw, ah, scale = video.analysis_size(info.width, info.height, ww)
    p = Project(
        id=pid,
        name=name or src.name,
        source=source,
        info={
            "width": info.width, "height": info.height, "fps": info.fps, "frames": count,
            "has_audio": info.has_audio, "work_width": aw, "work_height": ah, "scale": scale,
        },
    )
    save(p)
    return p


# --------------------------------------------------------------------------- analysis


def add_trace(p: Project) -> Trace:
    if len(p.traces) >= MAX_TRACES:
        raise ValueError(f"at most {MAX_TRACES} shots per video")
    tr = Trace()
    p.traces.append(tr)
    return tr


def remove_trace(p: Project, key: str) -> None:
    if len(p.traces) <= 1:
        raise ValueError("a video keeps at least one shot")
    tr = p.trace(key)
    p.traces.remove(tr)
    shutil.rmtree(tracedir(p.id, tr), ignore_errors=True)


def analyze(p: Project, tr: Trace, progress=lambda f, msg="": None) -> Trace:
    if tr.shot.ball is None:
        raise AnalysisError("pick the ball first")
    src = source_path(p)
    fps = float(p.info["fps"])
    total = int(p.info["frames"])
    bf = tr.shot.ball_frame
    if not 0 <= bf < total - 3:
        raise AnalysisError(f"ball frame must be within 0..{total - 4}")
    a0 = max(0, bf - int(BEFORE_SECONDS * fps))
    a1 = min(total - 1, bf + int(AFTER_SECONDS * fps))
    ww = work_width_for(a1 - a0 + 1)
    d = tracedir(p.id, tr)
    d.mkdir(parents=True, exist_ok=True)
    masks_dir = d / "masks"
    shutil.rmtree(masks_dir, ignore_errors=True)
    masks_dir.mkdir()

    progress(0.02, "decoding + pose")
    pose = PoseRunner(fps)
    people: list[PersonFrame] = []
    ball_bgr: list[np.ndarray] = []

    def on_frame(i, bgr):  # i is local to the analysed range
        if a0 + i == bf:
            ball_bgr.append(bgr.copy())
        pf = pose.process(i, bgr)
        if pf.mask is not None:
            cv2.imwrite(str(masks_dir / f"{a0 + i:05d}.png"), pf.mask)
        people.append(PersonFrame(None, pf.hands, pf.height))
        if i % 10 == 0:
            progress(0.02 + 0.5 * i / max(1, a1 - a0), "decoding + pose")

    try:
        frames, scale = video.read_analysis_frames(src, ww, on_frame, start=a0, end=a1)
    finally:
        pose.close()
    n = len(frames)
    lb = bf - a0  # ball frame, local
    if lb >= n - 3:
        raise AnalysisError("not enough frames after the ball frame")

    progress(0.55, "camera motion")
    local_t = camera.estimate(frames, reference=lb)
    # Frames outside the analysed range reuse the nearest known camera pose.
    np.save(d / "transforms.npy", np.array([local_t[min(max(i - a0, 0), n - 1)] for i in range(total)]))

    progress(0.65, "ball")
    bx, by, radius = ball.refine_ball(frames[lb], (tr.shot.ball[0] / scale, tr.shot.ball[1] / scale), search=6)
    start = (bx, by)
    analysis: dict = {
        "scale": scale, "work_width": frames[0].shape[1], "frames": total, "range": [a0, a0 + n - 1],
        "start": start, "radius": radius, "reference": bf,
    }

    if tr.shot.mode == "flight":
        horizon = tr.shot.horizon / scale if tr.shot.horizon is not None else ball.estimate_horizon(frames[lb], by, ball_bgr[0] if ball_bgr else None)
        limits = trajectory.SHOT_TYPES[tr.shot.shot]
        imp = None if tr.shot.impact is None else tr.shot.impact - a0
        first = lb + 1 if imp is None else max(lb + 1, imp)
        last = n - 2 if imp is None else min(n - 2, imp + 4)
        w = frames[0].shape[1]
        max_area = (0.012 * w) ** 2
        cands = {t: ball.candidates(frames, local_t, t, max_area) for t in range(first, n - 1)}
        patch = ball.StartPatch(frames, local_t, lb, start, radius)
        launch = trajectory.detect_launch(cands, start, first, last + 2, 0.3 * w, 0.02 * w, gone=patch.gone)
        # When the address spot empties well before the detected launch (or nothing was
        # found), the real launch was missed: typically a fast ball that is blurred out for
        # a frame or two and slows sharply as it flies away from the camera. Look again
        # right where the ball left, tolerating those gaps.
        left = next((t for t in range(first, last + 1) if all(patch.gone(t + k) for k in (0, 2, 4))), None)
        if left is not None and (launch is None or launch[0] > left + fps):
            retry = trajectory.detect_launch(
                cands, start, left, min(left + int(fps), last + 2), 0.3 * w, 0.01 * w,
                gone=patch.gone, max_gap=3, min_ratio=0.15,
            )
            launch = retry or launch
        if launch is None:
            raise AnalysisError(
                "could not find the ball leaving its position. Check the ball position, "
                "or set the impact frame manually."
            )
        launch_frame, seed = launch
        found = trajectory.track(cands, seed, start, launch_frame, fps, limits, horizon, n - 2, max_misses=8)
        obs = [(c.frame, c.x, c.y) for c in found]
        base = trajectory.fit(obs, start, fps, (launch_frame - 1, launch_frame - 0.05), limits, horizon)
        base.t0 += a0
        local_impact = base.t0 - a0
        analysis.update(
            mode="flight", horizon=horizon, observations=[(f + a0, x, y) for f, x, y in obs],
            base_model=base.to_dict(), impact=base.t0,
        )
    else:
        local_impact, path = putt.track(frames, local_t, lb, start, radius, fps)
        analysis.update(mode="putt", observations=[(f + a0, x, y) for f, x, y in path], impact=local_impact + a0)

    progress(0.85, "swing")
    window = swing.swing_window(people, local_impact, fps)
    # The club head search needs the masks at analysis resolution.
    for i in range(window[0], window[1] + 1):
        m = cv2.imread(str(masks_dir / f"{a0 + i:05d}.png"), cv2.IMREAD_GRAYSCALE)
        people[i] = PersonFrame(m, people[i].hands, people[i].height)
    club, hands = swing.smooth(
        swing.club_path(frames, local_t, people, window), swing.hands_path(local_t, people, window)
    )
    analysis["swing"] = {
        "window": [window[0] + a0, window[1] + a0],
        "clubhead": [(f + a0, *rest) for f, *rest in club],  # (t, hx, hy, r, theta)
        "hands": [(f + a0, x, y) for f, x, y in hands],
    }
    tr.analysis = analysis
    tr.status, tr.error = "ready", None
    apply_edits(p, tr)
    progress(1.0, "done")
    return tr


# --------------------------------------------------------------------------- edits


def landing_ref(tr: Trace) -> tuple[float, float] | None:
    """User landing point in reference analysis coordinates."""
    if tr.shot.landing is None or not tr.analysis:
        return None
    s = tr.analysis["scale"]
    return (tr.shot.landing[0] / s, tr.shot.landing[1] / s)


# Typical carry (yd) -> hang time (s) for amateur-to-tour ball flights; interpolated.
HANG_TIME = ((5, 0.8), (20, 1.5), (40, 2.4), (70, 3.4), (100, 4.2), (150, 5.1), (200, 5.8), (250, 6.4), (300, 6.9), (350, 7.4))
_DISTANCE = re.compile(r"(\d+(?:[.,]\d+)?)\s*(m\b|meters?|metres?|미터|y|yds?|yards?|야드)?", re.IGNORECASE)


def parse_distance(text: str) -> float | None:
    """'245 yd' / '230m' / '210 야드' -> yards. A bare number is taken as yards."""
    m = _DISTANCE.search(text or "")
    if not m:
        return None
    value = float(m.group(1).replace(",", "."))
    unit = (m.group(2) or "").lower()
    if unit.startswith("m") or unit == "미터":
        value *= 1.0936
    return value if value > 0 else None


def distance_time(tr: Trace) -> float | None:
    """Hang time implied by a typed carry/distance pop-up (carry wins: it is where the ball lands)."""
    if tr.shot.mode != "flight":
        return None
    for kind in ("carry", "distance"):
        for pop in tr.popups:
            yards = parse_distance(pop.value) if pop.kind == kind else None
            if yards:
                xs, ys = zip(*HANG_TIME)
                return round(float(np.interp(yards, xs, ys)), 2)
    return None


def flight_time(tr: Trace) -> float | None:
    """Landing time: explicit user value, else the typed distance, else None (automatic)."""
    return tr.shot.flight_time if tr.shot.flight_time is not None else distance_time(tr)


def apply_edits(p: Project, tr: Trace) -> None:
    """Recompute the flight model from the auto fit plus user edits (fast)."""
    a = tr.analysis
    if not a or a.get("mode") != "flight":
        return
    base = Model.from_dict(a["base_model"])
    limits = trajectory.SHOT_TYPES[tr.shot.shot]
    m = trajectory.refit(
        base, a["observations"], float(p.info["fps"]), limits,
        landing=landing_ref(tr), flight_time=flight_time(tr), height=tr.shot.height,
    )
    a["model"] = m.to_dict()


# --------------------------------------------------------------------------- manual path


def has_path(tr: Trace) -> bool:
    """The shot has a ball path to draw (a trace may hold only swing points)."""
    a = tr.analysis
    return bool(a) and (a.get("mode") != "manual" or bool(tr.shot.manual and len(tr.shot.manual) >= 2))


def sync_manual(p: Project, tr: Trace) -> None:
    """Keep a hand-drawn path renderable without (or after a failed) analysis.

    With a drawn path and no analysis, a minimal "manual" analysis is created: a static
    camera (identity transforms) and no golfer masks. Clearing the path removes it."""
    a = tr.analysis
    wanted = bool(tr.shot.manual or tr.shot.swing_points)
    if wanted and not a:
        total = int(p.info["frames"])
        tracedir(p.id, tr).mkdir(parents=True, exist_ok=True)
        np.save(tracedir(p.id, tr) / "transforms.npy", np.tile(np.eye(3), (total, 1, 1)))
        tr.analysis = {
            "mode": "manual", "scale": float(p.info["scale"]), "work_width": int(p.info["work_width"]),
            "frames": total, "range": [0, total - 1], "observations": [],
            "impact": float(tr.shot.impact if tr.shot.impact is not None else tr.shot.manual_frame),
        }
        tr.status, tr.error = "ready", None
    elif not wanted and a and a.get("mode") == "manual":
        tr.analysis, tr.status = None, "new"
    elif a and a.get("mode") == "manual":
        a["impact"] = float(tr.shot.impact if tr.shot.impact is not None else tr.shot.manual_frame)


def reset(p: Project) -> Project:
    """Back to the state right after upload: default settings, no analysis or outputs."""
    d = workdir(p.id)
    shutil.rmtree(d / "shots", ignore_errors=True)
    for f in [*d.glob("logo.*"), *d.glob("*_traced.mp4")]:
        f.unlink(missing_ok=True)
    return Project(id=p.id, name=p.name, source=p.source, info=p.info)


def manual_duration(tr: Trace) -> float:
    """Seconds the hand-drawn path takes to animate."""
    if (ft := flight_time(tr)) is not None:
        return float(ft)
    a = tr.analysis or {}
    if a.get("mode") == "flight" and "model" in a:
        return float(Model.from_dict(a["model"]).flight_time)
    return 2.5 if tr.shot.mode == "putt" else float(trajectory.SHOT_TYPES[tr.shot.shot].typical)


def transforms(p: Project, tr: Trace) -> list[np.ndarray]:
    return list(np.load(tracedir(p.id, tr) / "transforms.npy"))


class ShotPath:
    """Time-parameterised shot path in reference analysis coordinates."""

    def __init__(self, p: Project, tr: Trace):
        a = tr.analysis
        self.fps = float(p.info["fps"])
        self.mode = a["mode"]
        if tr.shot.manual and len(tr.shot.manual) >= 2:
            self._manual(p, tr)
        elif self.mode == "flight":
            self.model = Model.from_dict(a["model"])
            self.t0 = self.model.t0
            self.end = self.model.flight_time
            self.times = np.linspace(0.0, self.end, max(2, int(self.end * self.fps * 6)))
            self.points = self.model.at(self.times)
            s = (1.0 - np.exp(-self.model.k * self.times)) / self.model.k
            self.depth = 1.0 / (1.0 + self.model.w * s)
        else:
            obs = np.array(a["observations"], dtype=np.float64)
            self.t0 = float(a["impact"])
            t = (obs[:, 0] - self.t0) / self.fps
            self.end = float(t[-1])
            self.times = np.linspace(0.0, self.end, max(2, int(self.end * self.fps * 4)))
            self.points = np.stack([np.interp(self.times, t, obs[:, 1]), np.interp(self.times, t, obs[:, 2])], 1)
            self.depth = np.ones(len(self.times))
        self.apex_index = int(np.argmin(self.points[:, 1])) if self.mode != "putt" else len(self.points) - 1

    def _manual(self, p: Project, tr: Trace) -> None:
        """Hand-drawn path, animated along its length with an ease-out (the ball slows)."""
        a = tr.analysis
        t = transforms(p, tr)
        frame = int(np.clip(tr.shot.manual_frame, 0, len(t) - 1))
        pts = np.asarray(tr.shot.manual, dtype=np.float64) / a["scale"]
        pts = camera.apply(t[frame], pts)  # -> reference analysis coordinates
        seg = np.hypot(*np.diff(pts, axis=0).T)
        arc = np.concatenate([[0.0], np.cumsum(seg)])
        arc /= max(arc[-1], 1e-9)
        self.mode = "manual"
        self.t0 = float(a["impact"])
        self.end = manual_duration(tr)
        self.times = np.linspace(0.0, self.end, max(2, int(self.end * self.fps * 6)))
        u = 1.0 - (1.0 - self.times / self.end) ** 1.6
        self.points = np.stack([np.interp(u, arc, pts[:, 0]), np.interp(u, arc, pts[:, 1])], 1)
        self.depth = np.ones(len(self.times))

    @property
    def apex_time(self) -> float:
        return float(self.times[self.apex_index])

    def point_at(self, t: float) -> np.ndarray:
        t = float(np.clip(t, 0.0, self.end))
        return np.array([np.interp(t, self.times, self.points[:, 0]), np.interp(t, self.times, self.points[:, 1])])
