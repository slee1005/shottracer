"""The "Create by AJC_shottracer" watermark and its password lock.

The watermark is drawn on every preview and export until it is unlocked with the
password. Only a salted scrypt hash of the password is kept here; the password itself
is never stored. The unlock applies to this computer (all projects) and is remembered in
WORKSPACE/../settings.json."""

from __future__ import annotations

import hashlib
import hmac
import json
from functools import lru_cache

import cv2
import numpy as np
from PIL import Image, ImageDraw

from . import engine, popups

TEXT = "Create by AJC_shottracer"

_SALT = bytes.fromhex("8566b868108a7e1ae56c2cd8d6ac2d02")
_HASH = bytes.fromhex("55096d2e3bc6cd19935aabf9257f8b342a20589ce94618219c52f05b7ee7a6c1")
_SCRYPT = dict(n=2**15, r=8, p=1, maxmem=64 * 1024 * 1024, dklen=32)


def check_password(password: str) -> bool:
    digest = hashlib.scrypt(password.encode("utf-8"), salt=_SALT, **_SCRYPT)
    return hmac.compare_digest(digest, _HASH)


def _settings_path():
    return engine.WORKSPACE.parent / "settings.json"


def _settings() -> dict:
    try:
        return json.loads(_settings_path().read_text())
    except (OSError, ValueError):
        return {}


def enabled() -> bool:
    return _settings().get("watermark", True) is not False


def set_enabled(on: bool) -> None:
    path = _settings_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    data = _settings()
    data["watermark"] = bool(on)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2))
    tmp.replace(path)


@lru_cache(maxsize=8)
def _badge(height: int) -> np.ndarray:
    """BGRA text with a soft dark outline, readable on any background."""
    font = popups._font(height, True)
    pad = max(2, height // 6)
    left, top, right, bottom = font.getbbox(TEXT)
    img = Image.new("RGBA", (right - left + 2 * pad, bottom - top + 2 * pad), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.text((pad - left, pad - top), TEXT, font=font, fill=(255, 255, 255, 235),
           stroke_width=max(1, height // 10), stroke_fill=(0, 0, 0, 170))
    return cv2.cvtColor(np.array(img), cv2.COLOR_RGBA2BGRA)


def draw(frame: np.ndarray) -> None:
    """Bottom centre, in place."""
    H, W = frame.shape[:2]
    badge = _badge(max(10, int(H * 0.032)))
    h, w = badge.shape[:2]
    popups.blend(frame, badge, (W - w) // 2, H - h - int(H * 0.025), 0.85)
