"""Pop-up cards (distance, speed, apex, …) and logo watermark, rendered with Pillow so
Korean text and anti-aliasing work."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT_CANDIDATES = [
    "/System/Library/Fonts/AppleSDGothicNeo.ttc",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/Library/Fonts/Arial Unicode.ttf",
    "C:/Windows/Fonts/malgunbd.ttf",  # Malgun Gothic Bold (Korean)
    "C:/Windows/Fonts/arialbd.ttf",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


@lru_cache(maxsize=32)
def _font(size: int, bold: bool) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            try:
                # AppleSDGothicNeo.ttc: index 6 is Bold, 0 Regular.
                index = (6 if bold else 0) if path.endswith("AppleSDGothicNeo.ttc") else 0
                return ImageFont.truetype(path, size, index=index)
            except OSError:
                continue
    return ImageFont.load_default(size)


def _hex(color: str) -> tuple[int, int, int]:
    c = color.lstrip("#")
    return int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16)


@lru_cache(maxsize=64)
def card(label: str, value: str, height: int, bg: str, accent: str) -> np.ndarray:
    """RGBA card: accent bar, small label above a large value."""
    pad = max(4, height // 6)
    lf = _font(max(8, int(height * 0.24)), False)
    vf = _font(max(10, int(height * 0.46)), True)
    tmp = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    lw = tmp.textlength(label, font=lf) if label else 0
    vw = tmp.textlength(value, font=vf) if value else 0
    bar = max(3, height // 12)
    width = int(max(lw, vw) + 2 * pad + bar + pad // 2)
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = max(3, height // 7)
    d.rounded_rectangle((0, 0, width - 1, height - 1), radius=r, fill=(*_hex(bg), 215))
    d.rounded_rectangle((0, 0, bar + r, height - 1), radius=r, fill=(*_hex(accent), 255))
    d.rectangle((bar, 0, bar + r, height - 1), fill=(*_hex(bg), 215))
    x = bar + pad
    if label:
        d.text((x, pad * 0.6), label, font=lf, fill=(200, 200, 200, 255))
        d.text((x, height * 0.36), value, font=vf, fill=(255, 255, 255, 255))
    else:
        d.text((x, height * 0.2), value, font=vf, fill=(255, 255, 255, 255))
    return cv2.cvtColor(np.array(img), cv2.COLOR_RGBA2BGRA)


def blend(frame: np.ndarray, rgba: np.ndarray, x: int, y: int, alpha: float = 1.0) -> None:
    """Alpha-composite a BGRA image onto `frame` with its top-left at (x, y), in place."""
    h, w = rgba.shape[:2]
    H, W = frame.shape[:2]
    x0, y0 = max(x, 0), max(y, 0)
    x1, y1 = min(x + w, W), min(y + h, H)
    if x1 <= x0 or y1 <= y0 or alpha <= 0:
        return
    src = rgba[y0 - y : y1 - y, x0 - x : x1 - x].astype(np.float32)
    a = src[..., 3:4] / 255.0 * alpha
    roi = frame[y0:y1, x0:x1].astype(np.float32)
    frame[y0:y1, x0:x1] = (roi * (1 - a) + src[..., :3] * a).astype(np.uint8)


@lru_cache(maxsize=8)
def _logo(path: str, width: int) -> np.ndarray | None:
    img = cv2.imread(path, cv2.IMREAD_UNCHANGED)
    if img is None:
        return None
    if img.ndim == 2:
        img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGRA)
    elif img.shape[2] == 3:
        img = cv2.cvtColor(img, cv2.COLOR_BGR2BGRA)
    h = max(1, round(img.shape[0] * width / img.shape[1]))
    return cv2.resize(img, (width, h), interpolation=cv2.INTER_AREA)


def logo(frame: np.ndarray, path: str, corner: str, size: float, opacity: float) -> None:
    H, W = frame.shape[:2]
    img = _logo(path, max(8, int(W * size)))
    if img is None:
        return
    m = int(0.03 * W)
    h, w = img.shape[:2]
    x = m if "left" in corner else W - w - m
    y = m if "top" in corner else H - h - m
    blend(frame, img, x, y, opacity)
