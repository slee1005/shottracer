"""Ball localisation at address, horizon estimate and in-flight candidate extraction.

All coordinates here are analysis-frame pixels (see video.read_analysis_frames)."""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

from . import camera


@dataclass(frozen=True)
class Candidate:
    frame: int
    x: float  # reference-frame coordinates
    y: float
    area: float
    strength: float


def refine_ball(frame: np.ndarray, click: tuple[float, float], search: int = 14) -> tuple[float, float, float]:
    """Snap a user click to the nearest compact bright blob. Returns (x, y, radius)."""
    h, w = frame.shape
    cx, cy = int(round(click[0])), int(round(click[1]))
    x0, y0 = max(cx - search, 0), max(cy - search, 0)
    x1, y1 = min(cx + search + 1, w), min(cy + search + 1, h)
    roi = cv2.GaussianBlur(frame[y0:y1, x0:x1], (3, 3), 0).astype(np.float32)
    # Ball = local brightness peak relative to its surroundings (grass/sky).
    background = cv2.blur(roi, (9, 9))
    contrast = roi - background
    _, peak, _, loc = cv2.minMaxLoc(contrast)
    if peak < 6:
        return float(click[0]), float(click[1]), 3.0
    mask = (contrast > peak * 0.45).astype(np.uint8)
    n, labels, stats, cents = cv2.connectedComponentsWithStats(mask)
    label = labels[loc[1], loc[0]]
    area = stats[label, cv2.CC_STAT_AREA]
    radius = float(np.clip(np.sqrt(area / np.pi), 1.5, 12))
    return float(x0 + cents[label][0]), float(y0 + cents[label][1]), radius


def estimate_horizon(frame: np.ndarray, below: float, bgr: np.ndarray | None = None) -> float:
    """Where distant ground ends in the image, used as the landing row of far shots.

    Prefer the top of the grass (the far edge of the fairway): with tall trees the
    sky/tree edge sits far above the ground. Otherwise use the strongest
    bright-above/dark-below transition (sky -> trees/ground) above the ball, and finally
    40% of the height."""
    h = frame.shape[0]
    if bgr is not None:
        grass = _grass_line(bgr, below)
        if grass is not None:
            return grass
    rows = cv2.GaussianBlur(frame, (0, 0), 3).astype(np.float32).mean(axis=1)
    lo, hi = int(0.05 * h), int(min(below - 0.05 * h, 0.85 * h))
    if hi - lo < 10:
        return 0.4 * h
    step = max(3, h // 100)
    ys = np.arange(lo, hi)
    edge = rows[ys - step] - rows[np.minimum(ys + step, h - 1)]
    best = int(np.argmax(edge))
    if edge[best] < 8:
        return 0.4 * h
    return float(ys[best])


def _grass_line(bgr: np.ndarray, below: float) -> float | None:
    h = bgr.shape[0]
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    green = (
        (hsv[..., 0] >= 30) & (hsv[..., 0] <= 90) & (hsv[..., 1] >= 50) & (hsv[..., 2] >= 40)
    ).astype(np.float32)
    frac = cv2.GaussianBlur(green, (0, 0), 3).mean(axis=1)
    y = int(min(below, h - 1))
    if frac[y] < 0.5:  # the ball does not sit on a grass-dominated row
        return None
    while y > 0 and frac[y - 1] >= 0.5:
        y -= 1
    if not 0.15 * h <= y <= below - 0.03 * h:
        return None
    return float(y)


class StartPatch:
    """Appearance of the ball's address position, to tell when the ball has left it.

    A launch candidate is only real if the address spot stops looking like it did at
    address; club or hand motion elsewhere (e.g. late backswing) leaves it unchanged."""

    def __init__(self, frames, transforms, ball_frame: int, ball: tuple[float, float], radius: float):
        self.frames, self.transforms, self.ball_frame, self.ball = frames, transforms, ball_frame, ball
        self.half = int(max(4, round(radius * 2.5)))
        self.template = self._patch(ball_frame)

    def _patch(self, t: int) -> np.ndarray | None:
        x, y = camera.apply(camera.between(self.transforms, self.ball_frame, t), [self.ball])[0]
        xi, yi, h = int(round(x)), int(round(y)), self.half
        f = self.frames[t]
        if xi - h < 0 or yi - h < 0 or xi + h + 1 > f.shape[1] or yi + h + 1 > f.shape[0]:
            return None
        return f[yi - h : yi + h + 1, xi - h : xi + h + 1]

    def gone(self, t: int) -> bool:
        t = min(max(t, 0), len(self.frames) - 1)
        p = self._patch(t)
        if p is None or self.template is None:
            return True  # cannot judge: do not block the launch
        a = self.template.astype(np.float32) - self.template.mean()
        b = p.astype(np.float32) - p.mean()
        den = float(np.sqrt((a * a).sum() * (b * b).sum()))
        return (float((a * b).sum() / den) if den > 1e-6 else 0.0) < 0.5


def motion(frames: list[np.ndarray], transforms: list[np.ndarray], t: int) -> tuple[np.ndarray, np.ndarray]:
    """Three-frame difference of frame t after camera compensation: (diff, binary mask)."""
    cur = cv2.GaussianBlur(frames[t], (3, 3), 0).astype(np.int16)
    prev = cv2.GaussianBlur(camera.warp_to(frames[t - 1], transforms, t - 1, t), (3, 3), 0).astype(np.int16)
    nxt = cv2.GaussianBlur(camera.warp_to(frames[t + 1], transforms, t + 1, t), (3, 3), 0).astype(np.int16)
    diff = np.minimum(np.abs(cur - prev), np.abs(cur - nxt)).astype(np.uint8)
    noise = float(np.median(diff)) + 1.0
    thr = max(8.0, noise * 5.0)
    return diff, (diff > thr).astype(np.uint8)


def candidates(
    frames: list[np.ndarray],
    transforms: list[np.ndarray],
    t: int,
    max_area: float,
) -> list[Candidate]:
    """Small moving blobs in frame t (three-frame difference after camera compensation)."""
    if t <= 0 or t >= len(frames) - 1:
        return []
    diff, mask = motion(frames, transforms, t)
    n, labels, stats, cents = cv2.connectedComponentsWithStats(mask, connectivity=8)

    # Large moving regions (golfer, club, flags) and their surroundings are excluded.
    # A moving body breaks into many small fragments, so merge nearby motion first.
    merged = cv2.dilate(mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9)))
    mn, mlabels, mstats, _ = cv2.connectedComponentsWithStats(merged, connectivity=8)
    big_area = (0.03 * frames[t].shape[1]) ** 2
    big_ids = np.flatnonzero(mstats[:, cv2.CC_STAT_AREA] > big_area)
    big_ids = big_ids[big_ids != 0]
    big = np.isin(mlabels, big_ids).astype(np.uint8)
    big = cv2.dilate(big, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15)))

    out: list[Candidate] = []
    to_ref = transforms[t]
    for i in range(1, n):
        area = stats[i, cv2.CC_STAT_AREA]
        if area > max_area:
            continue
        x, y = cents[i]
        if big[int(round(y)), int(round(x))]:
            continue
        w, h = stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT]
        if max(w, h) > 6 * max(1, min(w, h)) and area > 12:  # long thin = club shaft / line
            continue
        strength = float(diff[labels == i].max())
        rx, ry = camera.apply(to_ref, [(x, y)])[0]
        out.append(Candidate(t, float(rx), float(ry), float(area), strength))
    return out
