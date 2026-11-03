"""Compile the actual mobile C ABI and exercise numeric parity and synthetic tracking.

No device or private video is required. This does not establish real-video accuracy.
"""
from __future__ import annotations

import ctypes as c
from pathlib import Path
import shutil
import subprocess

import numpy as np
import pytest

from shottracer.trajectory import Model

ROOT = Path(__file__).resolve().parents[1]


class Point(c.Structure):
    _fields_ = [(name, c.c_double) for name in ("time", "x", "y", "confidence")]


class NativeModel(c.Structure):
    _fields_ = [(name, c.c_double) for name in Model.__dataclass_fields__]


@pytest.fixture(scope="session")
def core(tmp_path_factory):
    compiler = shutil.which("clang++") or shutil.which("g++")
    if not compiler:
        pytest.skip("mobile core tests require a C++17 compiler")
    library = tmp_path_factory.mktemp("mobile-core") / "core.dylib"
    subprocess.run([compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror",
                    "-shared", "-fPIC", "-I", str(ROOT / "core/include"),
                    str(ROOT / "core/src/shottracer.cpp"), "-o", str(library)], check=True)
    lib = c.CDLL(str(library))
    lib.st_create.argtypes = [c.c_int, c.c_int, c.c_double, c.c_double]
    lib.st_create.restype = c.c_void_p
    lib.st_destroy.argtypes = [c.c_void_p]
    lib.st_push.argtypes = [c.c_void_p, c.c_void_p, c.c_int, c.c_double]
    lib.st_count.argtypes = [c.c_void_p]
    lib.st_point.argtypes = [c.c_void_p, c.c_int, c.POINTER(Point)]
    lib.st_render.argtypes = [c.c_void_p, c.c_double, c.c_void_p, c.c_int]
    lib.st_model_at.argtypes = [c.POINTER(NativeModel), c.c_double, c.POINTER(c.c_double), c.POINTER(c.c_double)]
    lib.st_model_apex.argtypes = [c.POINTER(NativeModel)]
    lib.st_model_apex.restype = c.c_double
    return lib


@pytest.mark.parametrize("lift,height,du,dv", [(0, 1, 0, 0), (3, 1.4, 70, -30), (-1, .5, -20, 50)])
def test_model_matches_desktop(core, lift, height, du, dv):
    model = Model(80, 160, 40, 12.5, 120, 2, 170, 22, .8, .35, 5,
                  height, lift, dv, .8, du)
    native = NativeModel(**model.to_dict())
    for time in np.linspace(0, 5, 101):
        x, y = c.c_double(), c.c_double()
        assert core.st_model_at(c.byref(native), time, c.byref(x), c.byref(y)) == 0
        np.testing.assert_allclose([x.value, y.value], model.at(time), atol=1e-10)
    assert core.st_model_apex(c.byref(native)) == pytest.approx(model.apex_time(), abs=1e-12)


def frame(x=None, y=None, background=None):
    image = np.full((180, 240), 35, np.uint8) if background is None else background.copy()
    if x is not None:
        yy, xx = np.ogrid[:180, :240]
        image[(xx - x) ** 2 + (yy - y) ** 2 <= 9] = 245
    return image


def push(core, tracker, image, time):
    return core.st_push(tracker, image.ctypes.data, image.strides[0], time)


@pytest.fixture
def tracker(core):
    handle = core.st_create(240, 180, 40, 150)
    assert handle
    yield handle
    core.st_destroy(handle)


def flight(core, tracker, background=None):
    assert push(core, tracker, frame(40, 150, background), 0) == 0
    for i in range(1, 9):
        push(core, tracker, frame(40 + i * 7, 150 - i * 8, background), i / 30)


def test_automatic_launch_and_observations(core, tracker):
    flight(core, tracker)
    assert core.st_count(tracker) == 9
    p = Point()
    assert core.st_point(tracker, 8, c.byref(p)) == 0
    assert (p.x, p.y) == pytest.approx((96, 86), abs=.5)
    assert p.time == pytest.approx(8 / 30)


def test_stationary_ball_and_unrelated_motion_do_not_launch(core, tracker):
    for i in range(12):
        image = frame(40, 150)
        image[40:44, 170 + i:174 + i] = 255
        assert push(core, tracker, image, i / 30) == 0
    assert core.st_count(tracker) == 0


def test_tracking_stops_without_inventing_observations(core, tracker):
    flight(core, tracker)
    count = core.st_count(tracker)
    for i in range(9, 20):
        status = push(core, tracker, frame(), i / 30)
    assert status == 2
    assert core.st_count(tracker) == count


def test_camera_translation_is_removed_from_observations(core, tracker):
    rng = np.random.default_rng(2)
    background = rng.integers(20, 90, size=(180, 240), dtype=np.uint8)
    for i in range(9):
        image = frame(40 + i * 7, 150 - i * 8, background)
        image = np.roll(image, i, axis=1)
        push(core, tracker, image, i / 30)
    assert core.st_count(tracker) == 9
    p = Point()
    core.st_point(tracker, 8, c.byref(p))
    assert p.x == pytest.approx(96, abs=.5)


def test_render_uses_source_time_and_clears_destination(core, tracker):
    flight(core, tracker)
    pixels = np.full((180, 240, 4), 127, dtype=np.uint8)
    assert core.st_render(tracker, 0, pixels.ctypes.data, pixels.strides[0]) == 0
    assert not pixels.any()
    core.st_render(tracker, 8 / 30, pixels.ctypes.data, pixels.strides[0])
    assert pixels[86, 96, 3] == 255
    core.st_render(tracker, 2, pixels.ctypes.data, pixels.strides[0])
    assert not pixels.any()


def test_invalid_dimensions_timestamps_and_padded_stride(core, tracker):
    assert not core.st_create(4000, 180, 40, 150)
    image = frame(40, 150)
    padded = np.zeros((180, 256), dtype=np.uint8)
    padded[:, :240] = image
    assert push(core, tracker, padded, 0) == 0
    assert push(core, tracker, padded, 0) == -1
    assert push(core, tracker, padded, float("nan")) == -1
    assert core.st_push(tracker, padded.ctypes.data, 200, 1) == -1


def test_variable_frame_timestamps_keep_launch_time(core, tracker):
    for time in (0, .03, .07, .1, .15, .19):
        push(core, tracker, frame(round(40 + time * 200), round(150 - time * 240)), time)
    assert core.st_count(tracker) == 6
    point = Point()
    core.st_point(tracker, 0, c.byref(point))
    assert point.time == 0
    core.st_point(tracker, 5, c.byref(point))
    assert point.time == pytest.approx(.19)
