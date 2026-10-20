"""Project storage, analysis pipeline and edit application.

A project lives in WORKSPACE/<id>/:
    project.json      settings + analysis results
    source.<ext>      the video (or a reference to the original for CLI runs)
    previews/NNNNN.jpg  analysis-resolution frames for the UI
    masks/NNNNN.png   golfer segmentation (tracer cut-out)
    transforms.npy    per-frame camera transforms (frame -> reference)
"""

from __future__ import annotations

import os
import shutil
import threading
import uuid
from pathlib import Path

import cv2
import numpy as np

from . import ball, camera, putt, swing, trajectory, video
from .person import PersonFrame, PoseRunner
from .settings import Project
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


def source_path(p: Project) -> Path:
    src = Path(p.source)
    return src if src.is_absolute() else workdir(p.id) / src


def save(p: Project) -> None:
    d = workdir(p.id)
    d.mkdir(parents=True, exist_ok=True)
    tmp = d / "project.json.tmp"
    tmp.write_text(p.model_dump_json(indent=2))
    tmp.replace(d / "project.json")


def load(pid: str) -> Project:
    path = workdir(pid) / "project.json"
    if not path.exists():
        raise KeyError(pid)
    return Project.model_validate_json(path.read_text())


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


def analyze(p: Project, progress=lambda f, msg="": None) -> Project:
    if p.shot.ball is None:
        raise AnalysisError("pick the ball first")
    src = source_path(p)
    fps = float(p.info["fps"])
    total = int(p.info["frames"])
    bf = p.shot.ball_frame
    if not 0 <= bf < total - 3:
        raise AnalysisError(f"ball frame must be within 0..{total - 4}")
    a0 = max(0, bf - int(BEFORE_SECONDS * fps))
    a1 = min(total - 1, bf + int(AFTER_SECONDS * fps))
    ww = work_width_for(a1 - a0 + 1)
    d = workdir(p.id)
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
    bx, by, radius = ball.refine_ball(frames[lb], (p.shot.ball[0] / scale, p.shot.ball[1] / scale), search=6)
    start = (bx, by)
    analysis: dict = {
        "scale": scale, "work_width": frames[0].shape[1], "frames": total, "range": [a0, a0 + n - 1],
        "start": start, "radius": radius, "reference": bf,
    }

    if p.shot.mode == "flight":
        horizon = p.shot.horizon / scale if p.shot.horizon is not None else ball.estimate_horizon(frames[lb], by, ball_bgr[0] if ball_bgr else None)
        limits = trajectory.SHOT_TYPES[p.shot.shot]
        imp = None if p.shot.impact is None else p.shot.impact - a0
        first = lb + 1 if imp is None else max(lb + 1, imp)
        last = n - 2 if imp is None else min(n - 2, imp + 4)
        w = frames[0].shape[1]
        max_area = (0.012 * w) ** 2
        cands = {t: ball.candidates(frames, local_t, t, max_area) for t in range(first, n - 1)}
        patch = ball.StartPatch(frames, local_t, lb, start, radius)
        launch = trajectory.detect_launch(cands, start, first, last + 2, 0.3 * w, 0.02 * w, gone=patch.gone)
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
    p.analysis = analysis
    p.status, p.error = "ready", None
    apply_edits(p)
    progress(1.0, "done")
    return p


# --------------------------------------------------------------------------- edits


def landing_ref(p: Project) -> tuple[float, float] | None:
    """User landing point in reference analysis coordinates."""
    if p.shot.landing is None or not p.analysis:
        return None
    s = p.analysis["scale"]
    return (p.shot.landing[0] / s, p.shot.landing[1] / s)


def apply_edits(p: Project) -> None:
    """Recompute the flight model from the auto fit plus user edits (fast)."""
    a = p.analysis
    if not a or a.get("mode") != "flight":
        return
    base = Model.from_dict(a["base_model"])
    limits = trajectory.SHOT_TYPES[p.shot.shot]
    m = trajectory.refit(
        base, a["observations"], float(p.info["fps"]), limits,
        landing=landing_ref(p), flight_time=p.shot.flight_time, height=p.shot.height,
    )
    a["model"] = m.to_dict()


def transforms(p: Project) -> list[np.ndarray]:
    return list(np.load(workdir(p.id) / "transforms.npy"))


class ShotPath:
    """Time-parameterised shot path in reference analysis coordinates."""

    def __init__(self, p: Project):
        a = p.analysis
        self.fps = float(p.info["fps"])
        self.mode = a["mode"]
        if self.mode == "flight":
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
        self.apex_index = int(np.argmin(self.points[:, 1])) if self.mode == "flight" else len(self.points) - 1

    @property
    def apex_time(self) -> float:
        return float(self.times[self.apex_index])

    def point_at(self, t: float) -> np.ndarray:
        t = float(np.clip(t, 0.0, self.end))
        return np.array([np.interp(t, self.times, self.points[:, 0]), np.interp(t, self.times, self.points[:, 1])])
