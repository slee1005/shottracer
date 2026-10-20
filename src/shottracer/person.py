"""Golfer segmentation (for the tracer cut-out) and wrist positions (for the swing path)
using MediaPipe Pose Landmarker. The model is downloaded once and verified."""

from __future__ import annotations

import hashlib
import os
import urllib.request
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
    "pose_landmarker_full/float16/latest/pose_landmarker_full.task"
)
MODEL_SHA256 = "4eaa5eb7a98365221087693fcc286334cf0858e2eb6e15b506aa4a7ecdcec4ad"
LEFT_WRIST, RIGHT_WRIST = 15, 16
LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_ANKLE, RIGHT_ANKLE = 11, 12, 27, 28


def model_path() -> Path:
    cache = Path(os.environ.get("SHOTTRACER_CACHE", Path.home() / "Library" / "Caches" / "shottracer"))
    path = cache / "models" / "pose_landmarker_full.task"
    if path.exists() and _sha256(path) == MODEL_SHA256:
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".part")
    urllib.request.urlretrieve(MODEL_URL, tmp)
    if _sha256(tmp) != MODEL_SHA256:
        tmp.unlink(missing_ok=True)
        raise RuntimeError("downloaded pose model does not match the pinned SHA-256")
    tmp.replace(path)
    return path


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


@dataclass
class PersonFrame:
    mask: np.ndarray | None  # uint8 0..255 at analysis resolution
    hands: tuple[float, float] | None  # wrist midpoint, analysis pixels
    height: float | None  # shoulder-to-ankle span (scale for club length)


class PoseRunner:
    """Sequential (video-mode) pose + segmentation."""

    def __init__(self, fps: float):
        import mediapipe as mp
        from mediapipe.tasks.python import BaseOptions, vision

        self._mp = mp
        self._fps = fps
        options = vision.PoseLandmarkerOptions(
            base_options=BaseOptions(model_asset_path=str(model_path())),
            running_mode=vision.RunningMode.VIDEO,
            output_segmentation_masks=True,
            num_poses=1,
        )
        self._landmarker = vision.PoseLandmarker.create_from_options(options)

    def process(self, index: int, bgr: np.ndarray) -> PersonFrame:
        h, w = bgr.shape[:2]
        image = self._mp.Image(image_format=self._mp.ImageFormat.SRGB, data=cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
        result = self._landmarker.detect_for_video(image, int(round(index * 1000 / self._fps)))
        mask = None
        if result.segmentation_masks:
            m = result.segmentation_masks[0].numpy_view()
            m = m[..., 0] if m.ndim == 3 else m
            mask = np.clip(m * 255, 0, 255).astype(np.uint8)
        hands = height = None
        if result.pose_landmarks:
            p = result.pose_landmarks[0]
            hands = ((p[LEFT_WRIST].x + p[RIGHT_WRIST].x) / 2 * w, (p[LEFT_WRIST].y + p[RIGHT_WRIST].y) / 2 * h)
            top = (p[LEFT_SHOULDER].y + p[RIGHT_SHOULDER].y) / 2 * h
            bottom = (p[LEFT_ANKLE].y + p[RIGHT_ANKLE].y) / 2 * h
            height = max(1.0, bottom - top)
        return PersonFrame(mask, hands, height)

    def close(self) -> None:
        self._landmarker.close()
