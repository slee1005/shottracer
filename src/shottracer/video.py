"""Video decoding (analysis copy) and ffmpeg-based encoding with audio passthrough."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


@dataclass(frozen=True)
class VideoInfo:
    width: int
    height: int
    fps: float
    frame_count: int
    has_audio: bool


def probe(path: Path) -> VideoInfo:
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise ValueError(f"cannot open video: {path}")
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    cap.release()
    return VideoInfo(width, height, fps, count, _has_audio(path))


# Windowed apps on Windows would flash a console for every ffmpeg call.
NO_WINDOW = {"creationflags": subprocess.CREATE_NO_WINDOW} if sys.platform == "win32" else {}


def _has_audio(path: Path) -> bool:
    if shutil.which("ffprobe"):
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "json", str(path)],
            capture_output=True, text=True, **NO_WINDOW,
        )
        try:
            return bool(json.loads(out.stdout or "{}").get("streams"))
        except json.JSONDecodeError:
            return False
    if shutil.which("ffmpeg"):  # the standalone app ships ffmpeg only: read its stream list
        out = subprocess.run(["ffmpeg", "-hide_banner", "-i", str(path)], capture_output=True, text=True,
                             errors="replace", **NO_WINDOW)
        return any("Stream #" in line and "Audio:" in line for line in out.stderr.splitlines())
    return False


def analysis_size(width: int, height: int, work_width: int) -> tuple[int, int, float]:
    """(w, h, scale) of the analysis frames; scale maps analysis pixels to video pixels."""
    if width <= work_width:
        return width, height, 1.0
    scale = width / work_width
    return work_width, round(height / scale), scale


def read_analysis_frames(
    path: Path, work_width: int, on_frame=None, start: int = 0, end: int | None = None
) -> tuple[list[np.ndarray], float]:
    """Decode frames start..end (inclusive) as downscaled grayscale images. Returns
    (frames, scale) where scale maps analysis pixels to original pixels and frames[0] is
    frame `start`. `on_frame(index, small_bgr)` is called for each decoded frame (used to
    run pose estimation in the same pass)."""
    cap = cv2.VideoCapture(str(path))
    for _ in range(start):  # sequential skip: seeking is not frame-accurate for all codecs
        if not cap.grab():
            break
    frames: list[np.ndarray] = []
    scale = 1.0
    while end is None or start + len(frames) <= end:
        ok, frame = cap.read()
        if not ok:
            break
        h, w = frame.shape[:2]
        aw, ah, scale = analysis_size(w, h, work_width)
        if scale != 1.0:
            frame = cv2.resize(frame, (aw, ah), interpolation=cv2.INTER_AREA)
        if on_frame is not None:
            on_frame(len(frames), frame)
        frames.append(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY))
    cap.release()
    if not frames:
        raise ValueError(f"no frames decoded from {path}")
    return frames, scale


def write_previews(path: Path, out_dir: Path, work_width: int, quality: int = 85) -> int:
    """Store every frame as a JPEG at analysis resolution (same decoder and frame
    numbering as analysis and rendering). Returns the frame count."""
    out_dir.mkdir(parents=True, exist_ok=True)
    count = 0
    for i, frame in enumerate(iter_frames(path)):
        h, w = frame.shape[:2]
        aw, ah, scale = analysis_size(w, h, work_width)
        if scale != 1.0:
            frame = cv2.resize(frame, (aw, ah), interpolation=cv2.INTER_AREA)
        cv2.imwrite(str(out_dir / f"{i:05d}.jpg"), frame, [cv2.IMWRITE_JPEG_QUALITY, quality])
        count = i + 1
    return count


def iter_frames(path: Path):
    cap = cv2.VideoCapture(str(path))
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                return
            yield frame
    finally:
        cap.release()


class Writer:
    """Pipes BGR frames to ffmpeg (H.264), muxing the source audio when present.
    Falls back to OpenCV's mp4v writer (no audio) when ffmpeg is unavailable."""

    def __init__(self, out: Path, source: Path, info: VideoInfo, audio: bool = True):
        self._ffmpeg = None
        self._cv = None
        if shutil.which("ffmpeg"):
            cmd = [
                "ffmpeg", "-v", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{info.width}x{info.height}",
                "-r", f"{info.fps}", "-i", "-",
            ]
            if audio and info.has_audio:
                # No -shortest: the video defines the length even when the audio is shorter.
                cmd += ["-i", str(source), "-map", "0:v:0", "-map", "1:a:0", "-c:a", "copy"]
            cmd += ["-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
                    "-movflags", "+faststart", str(out)]
            self._ffmpeg = subprocess.Popen(cmd, stdin=subprocess.PIPE, **NO_WINDOW)
        else:
            fourcc = cv2.VideoWriter.fourcc(*"mp4v")
            self._cv = cv2.VideoWriter(str(out), fourcc, info.fps, (info.width, info.height))

    def write(self, frame: np.ndarray) -> None:
        if self._ffmpeg is not None:
            assert self._ffmpeg.stdin is not None
            self._ffmpeg.stdin.write(np.ascontiguousarray(frame).tobytes())
        else:
            self._cv.write(frame)

    def close(self) -> None:
        if self._ffmpeg is not None:
            assert self._ffmpeg.stdin is not None
            self._ffmpeg.stdin.close()
            if self._ffmpeg.wait() != 0:
                raise RuntimeError("ffmpeg encoding failed")
        else:
            self._cv.release()
