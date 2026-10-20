"""Swing path: club head (or hands) positions through the swing.

The club head is found per frame as the farthest moving pixel connected to the hands
once the golfer's own silhouette is removed from the motion mask: what remains near the
hands is the shaft and head.

Detections are smoothed in polar coordinates around the hands (club length r, angle
theta): a driver head can sweep ~170 degrees between two 30 fps frames near impact, so
the angle carries the motion while r only changes with foreshortening. Robust
(Hampel) outlier rejection keeps fast monotonic rotation, Savitzky-Golay smoothing
keeps its speed, and rendering interpolates arcs (see `densify`)."""

from __future__ import annotations

import math

import cv2
import numpy as np

from . import ball, camera
from .person import PersonFrame


def swing_window(people: list[PersonFrame], impact: float | None, fps: float) -> tuple[int, int]:
    """From the first clear hand movement before impact to the follow-through."""
    n = len(people)
    end = n - 2 if impact is None else min(n - 2, int(impact + 0.8 * fps))
    anchor = int(impact) if impact is not None else n // 2
    hands = [p.hands for p in people]
    heights = [p.height for p in people if p.height]
    if not heights:
        return 1, end
    scale = float(np.median(heights))
    # Walk back from impact to where the hands were still (address).
    still = 0
    start = 1
    for i in range(min(anchor, n - 1), 1, -1):
        if hands[i] and hands[i - 1] and math.dist(hands[i], hands[i - 1]) < 0.004 * scale * (30 / fps):
            still += 1
            if still >= max(3, int(0.15 * fps)):
                start = i
                break
        else:
            still = 0
    return max(1, start), end


def club_path(
    frames: list[np.ndarray],
    transforms: list[np.ndarray],
    people: list[PersonFrame],
    window: tuple[int, int],
) -> list[tuple[int, float, float]]:
    """Club head positions (frame, x, y) in reference coordinates."""
    raw: dict[int, tuple[float, float]] = {}
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))
    for t in range(window[0], window[1] + 1):
        p = people[t]
        if p.hands is None or p.height is None or t <= 0 or t >= len(frames) - 1:
            continue
        _, mask = ball.motion(frames, transforms, t)
        if p.mask is not None:
            body = cv2.dilate((p.mask > 128).astype(np.uint8), kernel)
            mask = mask & (1 - body)
        hx, hy = p.hands
        reach = 0.95 * p.height
        near = 0.35 * p.height
        ys, xs = np.nonzero(mask)
        if len(xs) == 0:
            continue
        d = np.hypot(xs - hx, ys - hy)
        keep = d <= reach
        if not keep.any():
            continue
        sub = np.zeros_like(mask)
        sub[ys[keep], xs[keep]] = 1
        sub = cv2.dilate(sub, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
        n, labels = cv2.connectedComponents(sub, connectivity=8)
        best = None
        for label in range(1, n):
            ly, lx = np.nonzero((labels == label) & (mask > 0))
            if len(lx) < 3:
                continue
            ld = np.hypot(lx - hx, ly - hy)
            if ld.min() > near:  # not attached to the hands: not the club
                continue
            j = int(np.argmax(ld))
            if ld[j] > 0.3 * p.height and (best is None or ld[j] > best[0]):
                best = (ld[j], lx[j], ly[j])
        if best is not None:
            raw[t] = tuple(camera.apply(transforms[t], [(best[1], best[2])])[0])
    return [(t, x, y) for t, (x, y) in sorted(raw.items())]


def hands_path(
    transforms: list[np.ndarray], people: list[PersonFrame], window: tuple[int, int]
) -> list[tuple[int, float, float]]:
    raw = {
        t: tuple(camera.apply(transforms[t], [people[t].hands])[0])
        for t in range(window[0], window[1] + 1)
        if people[t].hands is not None
    }
    return [(t, x, y) for t, (x, y) in sorted(raw.items())]


MAX_GAP = 4  # frames bridged by interpolation; longer gaps split the path


def smooth(
    club: list[tuple[int, float, float]], hands: list[tuple[int, float, float]]
) -> tuple[list[tuple], list[tuple]]:
    """Returns (club as (t, hx, hy, r, theta) rows, hands as (t, x, y) rows)."""
    hands_s = _smooth_xy(hands)
    H = {t: (x, y) for t, x, y in hands_s}
    rows = [(t, x, y) for t, x, y in club if t in H]
    if len(rows) < 4:
        return [], hands_s
    t = np.array([r[0] for r in rows], dtype=np.float64)
    hx = np.array([H[int(f)][0] for f in t])
    hy = np.array([H[int(f)][1] for f in t])
    dx = np.array([r[1] for r in rows]) - hx
    dy = np.array([r[2] for r in rows]) - hy
    radius = np.hypot(dx, dy)
    angle = np.arctan2(dy, dx)
    t, radius, theta = _reject_outliers(t, radius, angle)
    if len(t) < 4:
        return [], hands_s
    # Smooth at the detection times themselves; frames in between are produced by
    # `densify`, which knows when an arc is trustworthy.
    log_r = _savgol_t(t, np.log(radius), 3.0, 2)
    theta = _savgol_t(t, theta, 2.0, 2)
    rows = [(int(f), *H[int(f)], float(np.exp(lr)), float(th)) for f, lr, th in zip(t, log_r, theta)]
    return rows, hands_s


# Arcs around the hands assume the club rotates in the image plane. Between samples
# far apart in angle (fast downswing seen from behind) the swing plane is foreshortened,
# so the radius is pulled in toward the middle of such intervals with a sin^2 profile:
# zero slope at both samples keeps the path free of kinks.
DIP_START_DEG = 90.0
DIP_FULL_DEG = 180.0
MAX_DIP = 0.45


def densify(rows: list[tuple], per_frame: int = 24) -> np.ndarray:
    """Sub-frame samples (t, x, y) along the path. Club rows (t, hx, hy, r, theta) are
    interpolated as arcs around the hands (see DIP_*); hand rows (t, x, y) use
    Catmull-Rom splines. Segments separated by gaps are joined by NaN rows."""
    if len(rows) < 2:
        return np.empty((0, 3))
    arr = np.asarray(rows, dtype=np.float64)
    pieces = []
    for seg in _segments(arr[:, 0]):
        part = arr[seg]
        if len(part) < 2:
            continue
        if part.shape[1] == 5:
            t, hx, hy, r, th = _catmull_rom(part, per_frame).T
            idx = np.arange(len(t))
            interval = np.minimum(idx // per_frame, len(part) - 2)
            s = np.where(idx // per_frame > len(part) - 2, 1.0, (idx % per_frame) / per_frame)
            step = np.degrees(np.abs(np.diff(part[:, 4])))
            depth = MAX_DIP * np.clip((step - DIP_START_DEG) / (DIP_FULL_DEG - DIP_START_DEG), 0.0, 1.0)
            r = r * (1.0 - depth[interval] * np.sin(np.pi * s) ** 2)
            dense = np.stack([t, hx + r * np.cos(th), hy + r * np.sin(th)], axis=1)
        else:
            dense = _catmull_rom(part, per_frame)
        pieces.append(dense)
        pieces.append(np.full((1, 3), np.nan))
    return np.vstack(pieces[:-1]) if pieces else np.empty((0, 3))


def _smooth_xy(rows):
    if len(rows) < 3:
        return rows
    arr = np.asarray(rows, dtype=np.float64)
    out = []
    for seg in _segments(arr[:, 0]):
        part = arr[seg]
        full = np.arange(part[0, 0], part[-1, 0] + 1)
        xs = _savgol(np.interp(full, part[:, 0], part[:, 1]), 3, 2)
        ys = _savgol(np.interp(full, part[:, 0], part[:, 2]), 3, 2)
        out += [(int(f), float(x), float(y)) for f, x, y in zip(full, xs, ys)]
    return out


def _segments(t: np.ndarray) -> list[np.ndarray]:
    """Index arrays of runs whose consecutive frames are at most MAX_GAP apart."""
    if len(t) == 0:
        return []
    breaks = np.flatnonzero(np.diff(t) > MAX_GAP) + 1
    return np.split(np.arange(len(t)), breaks)


def _unwrap_with_velocity(t: np.ndarray, angle: np.ndarray) -> np.ndarray:
    """Unwrap angles choosing the 2*pi branch that continues the recent angular velocity.
    Near impact the club turns ~90-180 degrees per frame, where the shortest-jump rule
    of np.unwrap picks the wrong direction."""
    out = np.array(angle, dtype=np.float64)
    for i in range(1, len(out)):
        if i >= 2 and t[i - 1] > t[i - 2]:
            w = (out[i - 1] - out[i - 2]) / (t[i - 1] - t[i - 2])
            pred = out[i - 1] + w * (t[i] - t[i - 1])
        else:
            pred = out[i - 1]
        out[i] += 2 * math.pi * round((pred - out[i]) / (2 * math.pi))
    return out


def _reject_outliers(t, radius, angle, max_iter: int = 20):
    """Iteratively drop the sample that deviates most from the time interpolation of
    its neighbours. The tolerance grows with how fast the neighbours change, so fast
    but consistent rotation survives while isolated jumps do not."""
    t, radius, angle = map(np.asarray, (t, radius, angle))
    for _ in range(max_iter):
        theta = _unwrap_with_velocity(t, angle)
        logr = np.log(radius)
        worst, worst_i = 1.0, -1
        for i in range(1, len(t) - 1):
            f = (t[i] - t[i - 1]) / (t[i + 1] - t[i - 1])
            for v, tol in ((theta, math.radians(25)), (logr, math.log(1.35))):
                pred = v[i - 1] + f * (v[i + 1] - v[i - 1])
                allow = max(tol, 0.5 * abs(v[i + 1] - v[i - 1]))
                score = abs(v[i] - pred) / allow
                if score > worst:
                    worst, worst_i = score, i
        if worst_i < 0:
            break
        t, radius, angle = (np.delete(a, worst_i) for a in (t, radius, angle))
        if len(t) < 4:
            break
    return t, radius, _unwrap_with_velocity(t, angle)


def _savgol_t(t: np.ndarray, y: np.ndarray, half: float, order: int) -> np.ndarray:
    """Savitzky-Golay on irregular samples: local polynomial over |t_j - t_i| <= half."""
    out = np.empty(len(y))
    for i in range(len(y)):
        sel = np.abs(t - t[i]) <= half
        k = t[sel] - t[i]
        deg = min(order, int(sel.sum()) - 1)
        out[i] = np.polyfit(k, y[sel], deg)[-1] if deg > 0 else y[i]
    return out


def _savgol(y: np.ndarray, half: int, order: int) -> np.ndarray:
    """Savitzky-Golay smoothing; windows shrink at the ends instead of padding."""
    n = len(y)
    if n <= order + 1:
        return y.copy()
    out = np.empty(n)
    for i in range(n):
        lo, hi = max(0, i - half), min(n, i + half + 1)
        k = np.arange(lo, hi) - i
        deg = min(order, hi - lo - 1)
        coef = np.polyfit(k, y[lo:hi], deg)
        out[i] = coef[-1]  # value of the local polynomial at k = 0
    return out


def _catmull_rom(points: np.ndarray, per: int) -> np.ndarray:
    """Uniform Catmull-Rom through all rows (every column interpolated), `per` samples
    per interval, endpoints included."""
    p = np.vstack([2 * points[0] - points[1], points, 2 * points[-1] - points[-2]])
    s = np.linspace(0.0, 1.0, per, endpoint=False)[:, None]
    s2, s3 = s * s, s * s * s
    out = []
    for i in range(1, len(p) - 2):
        p0, p1, p2, p3 = p[i - 1], p[i], p[i + 1], p[i + 2]
        out.append(
            0.5 * ((2 * p1) + (-p0 + p2) * s + (2 * p0 - 5 * p1 + 4 * p2 - p3) * s2 + (-p0 + 3 * p1 - 3 * p2 + p3) * s3)
        )
    out.append(points[-1][None])
    return np.vstack(out)
