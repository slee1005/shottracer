"""Camera motion estimation so ball positions can be expressed in one reference frame.

Each frame gets a 3x3 similarity transform that maps its pixel coordinates into the
coordinates of a chosen reference frame (normally the impact frame)."""

from __future__ import annotations

import cv2
import numpy as np


def _pairwise(prev: np.ndarray, cur: np.ndarray) -> np.ndarray:
    """Transform mapping `cur` coordinates into `prev` coordinates."""
    pts = cv2.goodFeaturesToTrack(prev, maxCorners=600, qualityLevel=0.01, minDistance=8, blockSize=7)
    eye = np.eye(3)
    if pts is None or len(pts) < 12:
        return eye
    nxt, status, _ = cv2.calcOpticalFlowPyrLK(prev, cur, pts, None, winSize=(21, 21), maxLevel=3)
    good = status.reshape(-1) == 1
    if good.sum() < 12:
        return eye
    m, inliers = cv2.estimateAffinePartial2D(
        nxt[good], pts[good], method=cv2.RANSAC, ransacReprojThreshold=1.5, maxIters=3000, confidence=0.995
    )
    if m is None or inliers is None or inliers.sum() < 10:
        return eye
    out = np.eye(3)
    out[:2] = m
    return out


def estimate(frames: list[np.ndarray], reference: int) -> list[np.ndarray]:
    """Return per-frame transforms (frame coords -> reference coords)."""
    n = len(frames)
    to_prev = [np.eye(3)] + [_pairwise(frames[i - 1], frames[i]) for i in range(1, n)]
    to_first = [np.eye(3)]
    for i in range(1, n):
        to_first.append(to_first[i - 1] @ to_prev[i])
    ref_inv = np.linalg.inv(to_first[reference])
    return [ref_inv @ t for t in to_first]


def apply(t: np.ndarray, pts: np.ndarray) -> np.ndarray:
    pts = np.asarray(pts, dtype=np.float64).reshape(-1, 2)
    h = np.hstack([pts, np.ones((len(pts), 1))]) @ t.T
    return h[:, :2]


def between(transforms: list[np.ndarray], src: int, dst: int) -> np.ndarray:
    """Transform mapping frame `src` coordinates into frame `dst` coordinates."""
    return np.linalg.inv(transforms[dst]) @ transforms[src]


def warp_to(frame: np.ndarray, transforms: list[np.ndarray], src: int, dst: int) -> np.ndarray:
    m = between(transforms, src, dst)[:2]
    h, w = frame.shape[:2]
    return cv2.warpAffine(frame, m, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
