"""Putts and chips that roll: follow the ball on the ground by template matching."""

from __future__ import annotations

import math

import cv2
import numpy as np

from . import camera
from .trajectory import AnalysisError


def track(
    frames: list[np.ndarray],
    transforms: list[np.ndarray],
    ball_frame: int,
    start: tuple[float, float],
    radius: float,
    fps: float,
) -> tuple[float, list[tuple[int, float, float]]]:
    """Returns (impact frame, [(frame, x, y)] in reference coordinates) for the rolling ball."""
    half = int(max(5, round(radius * 2.5)))
    h, w = frames[0].shape
    x, y = start
    xi, yi = int(round(x)), int(round(y))
    if xi - half < 0 or yi - half < 0 or xi + half >= w or yi + half >= h:
        raise AnalysisError("the ball is too close to the frame border")
    template = frames[ball_frame][yi - half : yi + half + 1, xi - half : xi + half + 1].astype(np.float32)

    pos = np.array([x, y])  # current position, frame coordinates
    vel = np.zeros(2)
    impact = None
    path: list[tuple[int, float, float]] = []
    lost = still = 0
    for t in range(ball_frame + 1, len(frames)):
        m = camera.between(transforms, t - 1, t)
        pred = camera.apply(m, [pos])[0] + vel
        reach = int(max(radius * 3 + 6, 1.5 * np.linalg.norm(vel) + 6))
        found = _match(frames[t], template, pred, reach, half)
        if found is None:
            lost += 1
            if impact is not None and lost > max(6, int(0.25 * fps)):
                break
            pos = pred
            continue
        new, score, patch = found
        lost = 0
        ref_start = camera.apply(camera.between(transforms, ball_frame, t), [start])[0]
        if impact is None and math.dist(new, ref_start) > max(2.0, radius):
            impact = t - 1
        if impact is not None:
            vel = 0.6 * vel + 0.4 * (new - pred + vel)
            path.append((t, *camera.apply(transforms[t], [new])[0]))
            still = still + 1 if np.linalg.norm(new - camera.apply(m, [pos])[0]) < 0.15 else 0
            if still > int(0.5 * fps):
                break
            if score > 0.7:
                template = 0.85 * template + 0.15 * patch
        pos = new
    if impact is None or len(path) < 3:
        raise AnalysisError("the ball never started rolling; check the ball position or frame")
    start_ref = camera.apply(transforms[ball_frame], [start])[0]
    path.insert(0, (impact, float(start_ref[0]), float(start_ref[1])))
    return float(impact), _smooth(path)


def _match(frame, template, pred, reach, half):
    h, w = frame.shape
    px, py = int(round(pred[0])), int(round(pred[1]))
    x0, y0 = max(px - reach - half, 0), max(py - reach - half, 0)
    x1, y1 = min(px + reach + half + 1, w), min(py + reach + half + 1, h)
    if x1 - x0 <= 2 * half + 1 or y1 - y0 <= 2 * half + 1:
        return None
    roi = frame[y0:y1, x0:x1].astype(np.float32)
    res = cv2.matchTemplate(roi, template, cv2.TM_CCOEFF_NORMED)
    _, score, _, loc = cv2.minMaxLoc(res)
    if score < 0.45:
        return None
    cx, cy = x0 + loc[0] + half, y0 + loc[1] + half
    patch = roi[loc[1] : loc[1] + 2 * half + 1, loc[0] : loc[0] + 2 * half + 1]
    return np.array([float(cx), float(cy)]), float(score), patch


def _smooth(path, k: int = 2):
    if len(path) <= 2 * k + 1:
        return path
    arr = np.array([(x, y) for _, x, y in path])
    out = arr.copy()
    for i in range(k, len(arr) - k):
        out[i] = arr[i - k : i + k + 1].mean(axis=0)
    return [(f, float(x), float(y)) for (f, _, _), (x, y) in zip(path, out)]
