"""Compositing: shot tracer, golfer cut-out, swing path, pop-ups and logo.

The same Renderer draws UI previews (analysis resolution) and final videos (source
resolution), so what the editor shows is what gets exported."""

from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

from . import camera, engine, popups, swing, video
from .settings import Project, Trace


def parse_color(text: str) -> tuple[int, int, int]:
    """'#RRGGBB' / 'RRGGBB' -> BGR."""
    c = text.lstrip("#")
    if len(c) != 6:
        raise ValueError(f"invalid color {text!r}; use RRGGBB")
    r, g, b = (int(c[i : i + 2], 16) for i in (0, 2, 4))
    return b, g, r


@dataclass(frozen=True)
class Look:
    """Effective drawing parameters after applying a preset."""

    glow: float
    core_ratio: float  # core width relative to the line
    core_from_color: bool  # core tinted from the line color instead of `core_color`
    outline: bool
    tail: float | None


PRESETS = {
    "classic": Look(glow=1.0, core_ratio=0.5, core_from_color=False, outline=True, tail=None),
    "tv": Look(glow=0.8, core_ratio=0.75, core_from_color=False, outline=True, tail=None),
    "neon": Look(glow=2.0, core_ratio=0.45, core_from_color=True, outline=False, tail=None),
    "comet": Look(glow=1.4, core_ratio=0.55, core_from_color=False, outline=True, tail=0.7),
    "minimal": Look(glow=0.0, core_ratio=0.0, core_from_color=False, outline=True, tail=None),
}


class Renderer:
    """Draws every analysed shot of a project onto a frame."""

    def __init__(self, p: Project, out_size: tuple[int, int]):
        self.p = p
        self.layers = [Layer(p, tr, out_size) for tr in p.traces if tr.analysis]
        if not self.layers:
            raise ValueError("project is not analysed")

    def draw(self, frame: np.ndarray, index: float) -> np.ndarray:
        out = frame.astype(np.float32)
        for layer in self.layers:  # each cut-out puts the golfer back in front of earlier shots too
            out = layer.tracer(out, frame, index)
        for layer in self.layers:
            layer.swing_path(out, index)
        img = np.clip(out, 0, 255).astype(np.uint8)
        for layer in self.layers:
            layer.popups(img, index)
        if self.p.logo.path:
            popups.logo(img, self.p.logo.path, self.p.logo.corner, self.p.logo.size, self.p.logo.opacity)
        return img


class Layer:
    """One traced shot: its path, camera transforms, cut-out masks, swing and pop-ups."""

    def __init__(self, p: Project, tr: Trace, out_size: tuple[int, int]):
        a = tr.analysis
        self.p, self.tr = p, tr
        self.w, self.h = out_size
        self.fps = float(p.info["fps"])
        self.scale = self.w / float(a.get("work_width", p.info["work_width"]))  # analysis px -> output px
        self.range_end = a.get("range", [0, int(p.info["frames"]) - 1])[1]
        self.transforms = engine.transforms(p, tr)
        self.path = engine.ShotPath(p, tr)
        self.masks = engine.tracedir(p.id, tr) / "masks"
        st = p.style
        base = PRESETS[st.preset]
        self.look = Look(
            glow=base.glow * st.glow,
            core_ratio=base.core_ratio,
            core_from_color=base.core_from_color,
            outline=base.outline,
            tail=st.tail if st.tail is not None else base.tail,
        )
        self.color = parse_color(tr.color or st.color)
        if self.look.core_from_color:
            self.core = tuple(int(c + (255 - c) * 0.7) for c in self.color)
        else:
            self.core = parse_color(st.core_color)
        self.base = max(1.5, self.h / 270.0) * st.width
        sw = a.get("swing", {})
        rows = sw.get(p.swing.source, []) if p.swing.enabled else []
        dense = swing.densify(rows)
        self.swing = dense if len(dense) >= 2 else None
        # The swing path fades out 1.5 s after the follow-through.
        self.swing_end = sw.get("window", [0, self.range_end])[1] + 1.5 * self.fps

    # -------------------------------------------------------------- helpers
    def _to_frame(self, index: float, pts: np.ndarray) -> np.ndarray:
        i = int(np.clip(math.floor(index), 0, len(self.transforms) - 1))
        return camera.apply(np.linalg.inv(self.transforms[i]), pts) * self.scale

    def _mask(self, index: float) -> np.ndarray | None:
        path = self.masks / f"{int(math.floor(index)):05d}.png"
        if not path.exists():  # no segmentation (e.g. a hand-drawn line without analysis)
            return None
        m = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        if m is None:
            return None
        m = cv2.resize(m, (self.w, self.h), interpolation=cv2.INTER_LINEAR)
        m = cv2.GaussianBlur(m, (0, 0), max(1.0, self.h / 540))
        return (m.astype(np.float32) / 255.0)[..., None]

    def _hold_alpha(self, t: float) -> float:
        # Camera motion is only known inside the analysed range: fade out after it.
        index = self.path.t0 + t * self.fps
        a = float(np.clip(1.0 - (index - self.range_end) / (0.5 * self.fps), 0.0, 1.0))
        hold = self.p.style.hold
        if hold is None:
            return a
        end = self.path.end + hold
        return a * (float(np.clip(1.0 - (t - end) / 0.25, 0.0, 1.0)) if t > end else 1.0)

    # -------------------------------------------------------------- drawing
    def tracer(self, out: np.ndarray, original: np.ndarray, index: float) -> np.ndarray:
        t = (index - self.path.t0) / self.fps
        drew_tracer = False
        if t > 0:
            alpha = self._hold_alpha(t)
            if alpha > 0:
                drew_tracer = self._tracer(out, index, t, alpha)
                if self.p.style.landing_marker and self.path.end <= t:
                    self._landing(out, index, t - self.path.end, alpha)
        if drew_tracer and self.p.style.cutout:
            m = self._mask(index)
            if m is not None:
                out = out * (1 - m) + original.astype(np.float32) * m
        return out

    def swing_path(self, out: np.ndarray, index: float) -> None:
        if self.swing is not None and index <= min(self.range_end, self.swing_end + 0.5 * self.fps):
            self._swing(out, index)

    def popups(self, img: np.ndarray, index: float) -> None:
        self._popups(img, index, (index - self.path.t0) / self.fps)

    def _tracer(self, out: np.ndarray, index: float, t: float, alpha: float) -> bool:
        path = self.path
        n = int(np.searchsorted(path.times, min(t, path.end), side="right"))
        if n < 2:
            return False
        first = 0
        tail = self.look.tail
        if tail:
            first = int(np.searchsorted(path.times, min(t, path.end) - tail, side="left"))
            if t > path.end + tail:
                return False
            first = min(first, n - 2)
        pts = self._to_frame(index, path.points[first:n])
        depth = path.depth[first:n]
        times = path.times[first:n]
        now = min(t, path.end)
        lk = self.look

        small = 0.5
        glow = np.zeros((int(self.h * small), int(self.w * small), 3), np.uint8) if lk.glow > 0 else None
        core_rgb = np.zeros((self.h, self.w, 3), np.uint8)
        core_a = np.zeros((self.h, self.w), np.uint8)
        ip = np.round(pts * 4).astype(np.int32)
        gp = np.round(pts * small * 4).astype(np.int32)
        for i in range(1, len(pts)):
            k = 0.35 + 0.65 * depth[i]
            fade = 1.0
            if tail:
                fade = float(np.clip(1.0 - (now - times[i]) / tail, 0.0, 1.0)) ** 0.8
            lw = max(1, int(round(self.base * k)))
            a0, a1 = tuple(ip[i - 1]), tuple(ip[i])
            if glow is not None:
                gw = max(1, int(round(self.base * 2.4 * k * small)))
                col = tuple(int(c * fade) for c in self.color)
                cv2.line(glow, tuple(gp[i - 1]), tuple(gp[i]), col, gw, cv2.LINE_AA, shift=2)
            cv2.line(core_rgb, a0, a1, self.color, lw + (2 if lk.outline else 0), cv2.LINE_AA, shift=2)
            cv2.line(core_a, a0, a1, int(255 * fade), lw + (2 if lk.outline else 0), cv2.LINE_AA, shift=2)
            if lk.core_ratio > 0:
                cw = max(1, int(round(lw * lk.core_ratio)))
                cv2.line(core_rgb, a0, a1, self.core, cw, cv2.LINE_AA, shift=2)
        if glow is not None:
            glow = cv2.GaussianBlur(glow, (0, 0), self.base * 1.5 * small * max(0.5, lk.glow))
            glow = cv2.resize(glow, (self.w, self.h), interpolation=cv2.INTER_LINEAR)
            out += glow.astype(np.float32) * (0.9 * min(lk.glow, 2.0) * alpha)
        a = (core_a.astype(np.float32) / 255.0 * alpha)[..., None]
        out[:] = out * (1 - a) + core_rgb.astype(np.float32) * a
        if self.p.style.ball_head and t < path.end:
            x, y = pts[-1]
            r = max(2, int(round(self.base * 0.9 * depth[-1] + 1)))
            cv2.circle(out, (int(round(x)), int(round(y))), r + 1, self.color, -1, cv2.LINE_AA)
            cv2.circle(out, (int(round(x)), int(round(y))), r, (255, 255, 255), -1, cv2.LINE_AA)
        return True

    def _landing(self, out: np.ndarray, index: float, since: float, alpha: float) -> None:
        x, y = self._to_frame(index, self.path.points[-1:])[0]
        c = (int(round(x)), int(round(y)))
        if since < 0.7:
            q = since / 0.7
            r = int(self.base * (2 + 10 * q))
            ring = np.zeros_like(out)
            cv2.ellipse(ring, c, (r, max(1, r // 3)), 0, 0, 360, self.color, max(1, int(self.base * 0.6)), cv2.LINE_AA)
            out += ring * ((1 - q) * alpha)
        cv2.ellipse(out, c, (int(self.base * 1.6), max(1, int(self.base * 0.55))), 0, 0, 360,
                    tuple(float(v) * alpha + 0 for v in self.color), -1, cv2.LINE_AA)

    def _swing(self, out: np.ndarray, index: float) -> None:
        pts = self.swing
        visible = pts[(pts[:, 0] <= index) | np.isnan(pts[:, 0])]
        visible = visible[: np.flatnonzero(~np.isnan(visible[:, 0]))[-1] + 1] if (~np.isnan(visible[:, 0])).any() else visible[:0]
        if len(visible) < 2:
            return
        xy = self._to_frame(index, visible[:, 1:])
        gap = np.isnan(xy).any(axis=1)
        xy = np.nan_to_num(xy)
        color = parse_color(self.p.swing.color)
        width = max(1, int(round(self.base * 0.8 * self.p.swing.width)))
        layer = np.zeros_like(out)
        alpha = np.zeros(out.shape[:2], np.float32)
        ip = np.round(xy * 4).astype(np.int32)
        n = len(ip)
        for i in range(1, n):
            if gap[i] or gap[i - 1]:  # segment break (tracking gap)
                continue
            a = 0.35 + 0.65 * (i / (n - 1)) if self.p.swing.gradient else 1.0
            cv2.line(layer, tuple(ip[i - 1]), tuple(ip[i]), color, width, cv2.LINE_AA, shift=2)
            cv2.line(alpha, tuple(ip[i - 1]), tuple(ip[i]), a, width, cv2.LINE_AA, shift=2)
        fade = float(np.clip(1.0 - (index - self.swing_end) / (0.5 * self.fps), 0.0, 1.0))
        a = alpha[..., None] * fade
        out[:] = out * (1 - a) + layer * a

    def _popups(self, img: np.ndarray, index: float, t: float) -> None:
        if not self.tr.popups:
            return
        path = self.path
        events = {"start": 0.0, "apex": path.apex_time, "landing": path.end}
        anchors = {
            "start": path.points[0],
            "apex": path.points[path.apex_index],
            "landing": path.points[-1],
        }
        height = max(24, int(self.h * 0.075))
        m = int(0.03 * self.w)
        for pop in self.tr.popups:
            appear = events.get(pop.anchor, 0.0) + pop.delay
            local = t - appear
            if local < 0 or (pop.duration is not None and local > pop.duration + 0.3):
                continue
            fade_in = min(1.0, local / 0.3)
            fade_out = 1.0
            if pop.duration is not None and local > pop.duration:
                fade_out = max(0.0, 1.0 - (local - pop.duration) / 0.3)
            value = pop.value or (f"{path.end:.1f}s" if pop.kind == "hangtime" else "")
            if not value and not pop.label:
                continue
            card = popups.card(pop.label, value, height, pop.color, pop.accent)
            ch, cw = card.shape[:2]
            if pop.anchor in anchors:
                x, y = self._to_frame(index, anchors[pop.anchor][None])[0]
                x, y = int(x - cw / 2), int(y - ch - height * 0.6)
            else:
                x = m if "left" in pop.anchor else self.w - cw - m
                y = m if "top" in pop.anchor else self.h - ch - m
            y += int((1 - fade_in) * height * 0.4)  # slide up while fading in
            x = int(np.clip(x, 0, max(0, self.w - cw)))
            y = int(np.clip(y, 0, max(0, self.h - ch)))
            popups.blend(img, card, x, y, fade_in * fade_out)


# ------------------------------------------------------------------ timeline / export


def timeline(p: Project) -> list[float]:
    """Output frame -> fractional source frame (slow motion around each shot's impact)."""
    n = int(p.info["frames"])
    fps = float(p.info["fps"])
    sm = p.slowmo
    impacts = sorted(float(tr.analysis["impact"]) for tr in p.traces if tr.analysis)
    if not sm.enabled or not impacts or sm.factor <= 1.0:
        return [float(i) for i in range(n)]
    windows: list[list[float]] = []
    for impact in impacts:
        a, b = max(0.0, impact - sm.before * fps), min(n - 1.0, impact + sm.after * fps)
        if windows and a <= windows[-1][1]:
            windows[-1][1] = max(windows[-1][1], b)  # overlapping windows merge
        else:
            windows.append([a, b])
    out: list[float] = []
    x = 0.0
    step = 1.0 / sm.factor
    for a, b in windows:
        out += [float(i) for i in range(int(math.ceil(x)), int(math.ceil(a)))]
        x = float(max(math.ceil(a), math.ceil(x)))
        while x < b:
            out.append(x)
            x += step
    out += [float(i) for i in range(int(math.ceil(x)), n)]
    return out


def preview(p: Project, index: int) -> np.ndarray:
    img = cv2.imread(str(engine.workdir(p.id) / "previews" / f"{index:05d}.jpg"))
    if img is None:
        raise KeyError(index)
    if not any(tr.analysis for tr in p.traces):
        return img
    r = Renderer(p, (img.shape[1], img.shape[0]))
    return r.draw(img, float(index))


def export(p: Project, out: Path, progress=lambda f: None) -> Path:
    info = video.probe(engine.source_path(p))
    r = Renderer(p, (info.width, info.height))
    plan = timeline(p)
    slow = len(plan) != int(p.info["frames"])
    writer = video.Writer(out, engine.source_path(p), info, audio=not slow)
    frames = video.iter_frames(engine.source_path(p))
    cur_i, cur, nxt = -1, None, None

    def advance():
        nonlocal cur_i, cur, nxt
        cur_i += 1
        cur = nxt if nxt is not None else next(frames, None)
        nxt = next(frames, None)

    try:
        advance()
        for j, x in enumerate(plan):
            while cur_i < int(math.floor(x)) and nxt is not None:
                advance()
            if cur is None:
                break
            frac = x - math.floor(x)
            img = cur if frac < 1e-6 or nxt is None else cv2.addWeighted(cur, 1 - frac, nxt, frac, 0)
            writer.write(r.draw(img, x))
            if j % 10 == 0:
                progress(j / len(plan))
    finally:
        writer.close()
    progress(1.0)
    return out
