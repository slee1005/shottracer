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
from shottracer.trajectory import fit, refit, with_apex, SHOT_TYPES

ROOT = Path(__file__).resolve().parents[1]


class Point(c.Structure):
    _fields_ = [(name, c.c_double) for name in ("time", "x", "y", "confidence")]


class NativeModel(c.Structure):
    _fields_ = [(name, c.c_double) for name in Model.__dataclass_fields__]


class CameraTransform(c.Structure):
    _fields_ = [(name, c.c_double) for name in ("time", "m00", "m01", "m02", "m10", "m11", "m12")]


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
    lib.st_set_mode.argtypes = [c.c_void_p,c.c_int]
    lib.st_use_desktop_tracking.argtypes = [c.c_void_p, c.c_double]
    lib.st_reference_rgba.argtypes = [c.c_void_p, c.c_void_p, c.c_int]
    lib.st_finish.argtypes = [c.c_void_p]
    lib.st_camera_transform.argtypes = [c.c_void_p, c.c_int, c.POINTER(CameraTransform)]
    lib.st_club_point.argtypes = [c.c_void_p]*4+[c.c_int]*2+[c.c_double]*7+[c.POINTER(Point)]
    lib.st_destroy.argtypes = [c.c_void_p]
    lib.st_push.argtypes = [c.c_void_p, c.c_void_p, c.c_int, c.c_double]
    lib.st_count.argtypes = [c.c_void_p]
    lib.st_tracker_model.argtypes = [c.c_void_p, c.POINTER(NativeModel)]
    lib.st_point.argtypes = [c.c_void_p, c.c_int, c.POINTER(Point)]
    lib.st_camera_count.argtypes = [c.c_void_p]
    lib.st_camera_point.argtypes = [c.c_void_p, c.c_int, c.POINTER(Point)]
    lib.st_curve_at.argtypes = [c.POINTER(c.c_double), c.c_double, c.c_double, c.c_double, c.c_double,
                               c.POINTER(c.c_double), c.POINTER(c.c_double)]
    lib.st_render.argtypes = [c.c_void_p, c.c_double, c.c_void_p, c.c_int]
    lib.st_model_at.argtypes = [c.POINTER(NativeModel), c.c_double, c.POINTER(c.c_double), c.POINTER(c.c_double)]
    lib.st_model_apex.argtypes = [c.POINTER(NativeModel)]
    lib.st_model_apex.restype = c.c_double
    lib.st_fit_model.argtypes = [c.c_void_p, c.c_int] + [c.c_double] * 8 + [c.POINTER(NativeModel)]
    lib.st_refit_model.argtypes = [c.POINTER(NativeModel), c.c_void_p, c.c_int, c.c_void_p, c.c_double, c.POINTER(NativeModel)]
    lib.st_with_apex.argtypes = [c.POINTER(NativeModel), c.c_void_p, c.c_int, c.c_double, c.POINTER(NativeModel)]
    lib.st_shot_info_profile.argtypes = [c.c_double] * 4 + [c.POINTER(c.c_double)] * 3
    lib.st_shot_info_profile.restype = None
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


@pytest.mark.parametrize(("carry", "total", "height", "expected"), [
    (0, 0, 0, (3.2, 1.0, 0.0)),
    (0, 150 * .9144, 0, (5.1, 1.0, 0.0)),
    (175 * .9144, 0, 0, (5.45, 1.0, 0.0)),
    (200 * .9144, 220 * .9144, 50, (5.8, 50 / (9.80665 * 5.8**2 / 8), .1)),
    (200 * .9144, 180 * .9144, 0, (5.8, 1.0, 0.0)),
])
def test_shot_info_profile(core, carry, total, height, expected):
    outputs = [c.c_double() for _ in range(3)]
    core.st_shot_info_profile(carry, total, height, 3.2, *(c.byref(value) for value in outputs))
    assert [value.value for value in outputs] == pytest.approx(expected)


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


@pytest.fixture
def desktop_tracker(core):
    handle = core.st_create(240, 180, 40, 150)
    assert handle and core.st_use_desktop_tracking(handle, 2.0) == 0
    yield handle
    core.st_destroy(handle)


def test_desktop_pipeline_tracks_actual_three_frame_detections(core, desktop_tracker):
    for i in range(4):
        assert push(core, desktop_tracker, frame(40, 150), i / 30) == 0
    for i in range(1, 11):
        push(core, desktop_tracker, frame(40 + 7 * i, 150 - 8 * i), (i + 3) / 30)
    assert core.st_finish(desktop_tracker) == 1
    assert core.st_count(desktop_tracker) >= 9
    rows = []
    for i in range(core.st_count(desktop_tracker)):
        point = Point()
        assert core.st_point(desktop_tracker, i, c.byref(point)) == 0
        rows.append(point)
    assert rows[0].time >= 3 / 30
    # The last input has no following image, so it is never invented as a detection.
    assert rows[-1].time == pytest.approx(12 / 30)
    assert (rows[-1].x, rows[-1].y) == pytest.approx((103, 78), abs=.6)
    model = NativeModel()
    assert core.st_tracker_model(desktop_tracker, c.byref(model)) == 0
    assert model.flight_time == pytest.approx(2.0)
    assert model.vh == pytest.approx(.4)


def test_desktop_pipeline_requires_address_departure(core, desktop_tracker):
    for i in range(16):
        image = frame(40, 150)
        image[80-i*2:84-i*2, 160+i:164+i] = 245
        push(core, desktop_tracker, image, i / 30)
    assert core.st_finish(desktop_tracker) == 0
    assert core.st_count(desktop_tracker) == 0


@pytest.mark.parametrize("angle,scale,dx,dy", [(0, 1, 3, -2), (1.5, 1.01, 1, 1), (-1.5, .99, -1, 2)])
def test_desktop_camera_preserves_similarity_in_portrait_coordinates(core, angle, scale, dx, dy):
    import cv2
    rng = np.random.default_rng(745)
    h, w = 240, 180
    background = cv2.GaussianBlur(rng.integers(20, 130, (h, w), dtype=np.uint8), (3, 3), 0)
    matrix = cv2.getRotationMatrix2D((w / 2, h / 2), angle, scale)
    matrix[:, 2] += [dx, dy]
    moved = cv2.warpAffine(background, matrix, (w, h), borderMode=cv2.BORDER_REPLICATE)
    handle = core.st_create(w, h, 40, 200)
    try:
        assert core.st_use_desktop_tracking(handle, 0) == 0
        assert push(core, handle, background, 0) == 0
        assert push(core, handle, moved, 1 / 30) == 0
        camera = CameraTransform()
        assert core.st_camera_transform(handle, 1, c.byref(camera)) == 0
        actual = np.array([[camera.m00, camera.m01, camera.m02], [camera.m10, camera.m11, camera.m12]])
        for point in ((.2, .3), (.5, .5), (.75, .8)):
            expected = (matrix @ [point[0]*w, point[1]*h, 1]) / [w, h]
            np.testing.assert_allclose(actual @ [*point, 1], expected, atol=.003)
        assert core.st_count(handle) == 0
    finally:
        core.st_destroy(handle)


def test_desktop_pipeline_uses_color_grass_horizon_and_keeps_missing_frames_missing(core, desktop_tracker):
    rgba = np.full((180, 240, 4), 255, np.uint8)
    rgba[80:, :, :3] = [60, 140, 70]
    assert core.st_reference_rgba(desktop_tracker, rgba.ctypes.data, rgba.strides[0]) == 0
    push(core, desktop_tracker, frame(40, 150), 0)
    assert core.st_reference_rgba(desktop_tracker, rgba.ctypes.data, rgba.strides[0]) == -1
    for i in range(1, 12):
        image = frame() if i in (2, 5) else frame(40+7*i, 150-8*i)
        push(core, desktop_tracker, image, i/30)
    core.st_finish(desktop_tracker)
    model = NativeModel()
    assert core.st_tracker_model(desktop_tracker, c.byref(model)) == 0
    assert model.vh == pytest.approx(80/180, abs=2/180)
    for i in range(1, core.st_count(desktop_tracker)):
        point = Point()
        core.st_point(desktop_tracker, i, c.byref(point))
        assert point.time != pytest.approx(2/30)
        assert point.time != pytest.approx(5/30)


@pytest.mark.parametrize("ball_value,background", [(120,35),(35,130)])
def test_desktop_putt_keeps_dim_and_dark_ball_support(core, ball_value, background):
    yy,xx=np.ogrid[:180,:240]
    handle=core.st_create(240,180,40,150)
    try:
        assert core.st_set_mode(handle,1)==0
        assert core.st_use_desktop_tracking(handle,0)==0
        for i in range(16):
            image=np.full((180,240),background,np.uint8)
            x=40+max(0,i-3)*3
            image[(xx-x)**2+(yy-150)**2<=9]=ball_value
            assert push(core,handle,image,i/30)>=0
        core.st_finish(handle)
        assert core.st_count(handle)>=10
        point=Point()
        core.st_point(handle,core.st_count(handle)-1,c.byref(point))
        assert (point.x,point.y)==pytest.approx((76,150),abs=.6)
        assert core.st_tracker_model(handle,c.byref(NativeModel()))==-1
    finally:
        core.st_destroy(handle)


def test_desktop_pipeline_vfr_stride_finish_and_loss_do_not_invent_detections(core, desktop_tracker):
    times=[.0,.04,.075,.11,.15,.18,.22,.26,.295,.33,.37,.405,.44]
    for i,time in enumerate(times):
        padded=np.zeros((180,256),np.uint8)
        padded[:,:240]=frame(40+i*7,150-i*8)
        assert push(core,desktop_tracker,padded,time)>=0
    count=core.st_count(desktop_tracker)
    for i in range(1,12):
        push(core,desktop_tracker,frame(),times[-1]+i/30)
    assert core.st_finish(desktop_tracker)==2
    # At most the one delayed, genuine final ball frame may be added.
    assert count<=core.st_count(desktop_tracker)<=count+1
    points=[]
    for i in range(1,core.st_count(desktop_tracker)):
        point=Point(); core.st_point(desktop_tracker,i,c.byref(point)); points.append(point)
    assert all(any(abs(point.time-time)<1e-10 for time in times) for point in points)
    assert points[-1].time<=times[-1]
    assert push(core,desktop_tracker,frame(),1.0)==-1
    assert core.st_finish(desktop_tracker)==2


def test_desktop_rotating_camera_tracks_and_renders_in_the_same_frame(core, desktop_tracker):
    import cv2
    rng=np.random.default_rng(501)
    background=cv2.GaussianBlur(rng.integers(25,100,(180,240),dtype=np.uint8),(3,3),0)
    truth=Model(40,150,72,0,220,12,220,220*(1-np.exp(-.35*2))/.35/4,.2,.35,2)
    for i in range(15):
        x,y=truth.at(i/30)
        image=frame(x,y,background)
        matrix=cv2.getRotationMatrix2D((120,90),.3*i,1+.001*i)
        matrix[:,2]+=[i*.5,i*.2]
        transformed=cv2.warpAffine(image,matrix,(240,180),borderMode=cv2.BORDER_REPLICATE)
        assert push(core,desktop_tracker,transformed,i/30)>=0
    assert core.st_finish(desktop_tracker)==1
    last=Point()
    core.st_point(desktop_tracker,core.st_count(desktop_tracker)-1,c.byref(last))
    assert last.time==pytest.approx(13/30)
    np.testing.assert_allclose([last.x,last.y],truth.at(13/30),atol=1.2)
    model=NativeModel()
    assert core.st_tracker_model(desktop_tracker,c.byref(model))==0
    # The production path must keep the desktop regularized horizontal curve,
    # rather than resetting c to zero as the old mobile prototype did.
    assert abs(model.c)>1e-4
    camera=CameraTransform()
    assert core.st_camera_transform(desktop_tracker,14,c.byref(camera))==0
    x,y=c.c_double(),c.c_double()
    core.st_model_at(c.byref(model),14/30-model.t0,c.byref(x),c.byref(y))
    px=round((camera.m00*x.value+camera.m01*y.value+camera.m02)*240)
    py=round((camera.m10*x.value+camera.m11*y.value+camera.m12)*180)
    pixels=np.zeros((180,240,4),np.uint8)
    assert core.st_render(desktop_tracker,14/30,pixels.ctypes.data,pixels.strides[0])==0
    assert pixels[max(0,py-2):py+3,max(0,px-2):px+3,3].max()==255


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


def test_five_valid_moving_frames_are_required(core, tracker):
    push(core, tracker, frame(40, 150), 0)
    for i in range(1, 5):
        assert push(core, tracker, frame(40 + 7 * i, 150 - 8 * i), i / 30) == 0
    model = NativeModel()
    assert core.st_tracker_model(tracker, c.byref(model)) == -1
    assert push(core, tracker, frame(75, 110), 5 / 30) == 1
    assert core.st_count(tracker) == 6  # reference frame and five moving detections
    assert core.st_tracker_model(tracker, c.byref(model)) == 0
    assert model.t0 == 0
    assert model.b > 0 and model.g > 0


def test_launch_outside_fifty_degree_cone_is_rejected(core, tracker):
    push(core, tracker, frame(40, 150), 0)
    for i in range(1, 9):
        assert push(core, tracker, frame(40 + 10 * i, 150 - 7 * i), i / 30) == 0
    assert core.st_count(tracker) == 0


@pytest.mark.parametrize("outlier", [(110, 86), (124, 56), (112, 96)])
def test_flight_rejects_turn_speed_spike_and_outside_point_then_recovers(core, tracker, outlier):
    flight(core, tracker)
    before = NativeModel()
    assert core.st_tracker_model(tracker, c.byref(before)) == 0
    push(core, tracker, frame(*outlier), 9 / 30)
    assert core.st_count(tracker) == 9
    after = NativeModel()
    assert core.st_tracker_model(tracker, c.byref(after)) == 0
    assert bytes(before) == bytes(after)
    push(core, tracker, frame(110, 70), 10 / 30)
    assert core.st_count(tracker) == 10


def test_flight_reacquires_a_detection_after_missing_frames(core, tracker):
    flight(core, tracker)
    model = NativeModel()
    assert core.st_tracker_model(tracker, c.byref(model)) == 0
    for i in range(9, 12):
        push(core, tracker, frame(), i / 30)
    x, y = c.c_double(), c.c_double()
    assert core.st_model_at(c.byref(model), 12 / 30 - model.t0, c.byref(x), c.byref(y)) == 0
    # Seven pixels from the prediction is outside the base gate but inside the recovery gate.
    push(core, tracker, frame(round(x.value * 240), round(y.value * 180) + 7), 12 / 30)
    assert core.st_count(tracker) == 10


def test_live_render_predicts_from_model_without_inventing_observations(core, tracker):
    flight(core, tracker)
    push(core, tracker, frame(), 9 / 30)
    assert core.st_count(tracker) == 9
    model = NativeModel()
    assert core.st_tracker_model(tracker, c.byref(model)) == 0
    pixels = np.full((180, 240, 4), 127, dtype=np.uint8)
    assert core.st_render(tracker, 0, pixels.ctypes.data, pixels.strides[0]) == 0
    assert not pixels.any()
    time = 10 / 30
    x, y = c.c_double(), c.c_double()
    assert core.st_model_at(c.byref(model), time, c.byref(x), c.byref(y)) == 0
    core.st_render(tracker, time, pixels.ctypes.data, pixels.strides[0])
    assert pixels[round(y.value * 180), round(x.value * 240), 3] == 255
    core.st_render(tracker, model.flight_time + 2, pixels.ctypes.data, pixels.strides[0])
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


@pytest.mark.parametrize("speed", [.5, 1, 4])
def test_edit_curve_hits_exact_start_apex_and_landing(core, speed):
    controls = (c.c_double * 6)(.15, .8, .55, .12, .86, .65)
    x, y = c.c_double(), c.c_double()
    for time, expected in [(0, [.15, .8]), (4 * .5 / speed, [.55, .12]), (4 / speed, [.86, .65])]:
        assert core.st_curve_at(controls, 4, .5, speed, time, c.byref(x), c.byref(y)) == 0
        np.testing.assert_allclose([x.value, y.value], expected, atol=1e-12)
    ys = []
    for time in np.linspace(0, 4 / speed, 101):
        core.st_curve_at(controls, 4, .5, speed, time, c.byref(x), c.byref(y))
        ys.append(y.value)
    assert np.all(np.diff(ys[:51]) <= 0)
    assert np.all(np.diff(ys[50:]) >= 0)


def test_camera_samples_preserve_reference_offsets(core, tracker):
    rng = np.random.default_rng(7)
    background = rng.integers(20, 90, size=(180, 240), dtype=np.uint8)
    for i in range(9):
        push(core, tracker, np.roll(frame(40, 150, background), i, axis=1), i / 30)
    assert core.st_camera_count(tracker) == 9
    point = Point()
    assert core.st_camera_point(tracker, 8, c.byref(point)) == 0
    assert point.x == pytest.approx(8)
    assert point.y == pytest.approx(0)
    assert point.time == pytest.approx(8 / 30)


def test_native_fit_and_landing_refit_match_desktop(core):
    base = Model(80, 150, 30, .2, 110, 1, 180, 30, .6, .35, 5.5)
    frames = np.arange(1, 20, dtype=np.float64)
    xy = base.at((frames - base.t0) / 30)
    points = np.column_stack([frames, xy])
    expected = fit(points.tolist(), (80, 150), 30, (0, .9), SHOT_TYPES['full'], 30)
    seconds = np.ascontiguousarray(np.column_stack([frames / 30, xy]))
    actual = NativeModel()
    assert core.st_fit_model(seconds.ctypes.data, len(seconds), 80, 150, 30, 0, .9 / 30,
                             3.5, 7.5, 5.5, c.byref(actual)) == 0
    for time in np.linspace(0, 5.5, 30):
        x, y = c.c_double(), c.c_double()
        core.st_model_at(c.byref(actual), time, c.byref(x), c.byref(y))
        np.testing.assert_allclose([x.value, y.value], expected.at(time), atol=1e-5)
    landing = np.array([210., 140.])
    edited = NativeModel()
    assert core.st_refit_model(c.byref(actual), seconds.ctypes.data, len(seconds), landing.ctypes.data,
                               4.2, c.byref(edited)) == 0
    expected_edit = refit(expected, points.tolist(), 30, SHOT_TYPES['full'], (210, 140), 4.2)
    for time in np.linspace(0, 4.2, 30):
        x, y = c.c_double(), c.c_double()
        core.st_model_at(c.byref(edited), time, c.byref(x), c.byref(y))
        np.testing.assert_allclose([x.value, y.value], expected_edit.at(time), atol=1e-5)
    assert actual.flight_time == 5.5  # Refit never overwrites the automatic model.
    reshaped = NativeModel()
    assert core.st_with_apex(c.byref(edited), seconds.ctypes.data, len(seconds), 2.2, c.byref(reshaped)) == 0
    expected_apex = with_apex(expected_edit, points.tolist(), 30, 2.2)
    for time in np.linspace(0, 4.2, 30):
        x, y = c.c_double(), c.c_double()
        core.st_model_at(c.byref(reshaped), time, c.byref(x), c.byref(y))
        np.testing.assert_allclose([x.value, y.value], expected_apex.at(time), atol=1e-5)


def test_putt_follows_dim_rolling_ball_and_stops(core, tracker):
    assert core.st_set_mode(tracker, 1) == 0
    for i in range(40):
        x = 40 + min(max(0, i-3), 14)*3
        image = frame(x,150)
        image[image > 200] = 110  # Below the flight detector's brightness threshold.
        assert push(core,tracker,image,i/30) >= 0
    assert core.st_count(tracker) >= 12
    p = Point(); core.st_point(tracker,core.st_count(tracker)-1,c.byref(p))
    assert p.x == pytest.approx(82,abs=.5)
    assert p.y == pytest.approx(150,abs=.5)
    assert core.st_set_mode(tracker,0) == -1
    count=core.st_count(tracker)
    push(core,tracker,frame(110,150),2)
    assert core.st_count(tracker) == count


def test_putt_camera_motion_is_not_ball_motion(core, tracker):
    core.st_set_mode(tracker,1)
    background=np.random.default_rng(12).integers(20,70,(180,240),dtype=np.uint8)
    for i in range(15):
        image=np.roll(frame(40,150,background),i,axis=1)
        push(core,tracker,image,i/30)
    assert core.st_count(tracker) == 0
    camera=Point(); core.st_camera_point(tracker,14,c.byref(camera))
    assert camera.x == pytest.approx(14)


def test_club_requires_motion_attached_to_hands_and_excludes_body(core):
    previous=np.full((180,240),30,np.uint8); current=previous.copy(); following=previous.copy()
    current[79:82,85:161]=240
    current[20:25,210:215]=255  # Unattached motion must not win.
    mask=np.zeros_like(current); mask[45:150,45:86]=255
    out=Point()
    args=[previous.ctypes.data,current.ctypes.data,following.ctypes.data,mask.ctypes.data,240,180,80,80,100,0,0,0,0,c.byref(out)]
    assert core.st_club_point(*args) == 1
    assert out.x == 160
    assert abs(out.y-80) <= 1
    mask[:]=255
    assert core.st_club_point(*args) == 0
    mask[:]=0
    args[1]=previous.ctypes.data
    assert core.st_club_point(*args) == 0


def motion_summary(core, rows):
    core.st_motion_summary.argtypes = [c.c_void_p, c.c_int, c.c_void_p]
    samples = np.asarray(rows, dtype=np.float64)
    result = np.full(5, np.nan)
    status = core.st_motion_summary(samples.ctypes.data, len(samples), result.ctypes.data)
    return status, result


def test_motion_summary_uses_source_pts_and_image_pixels(core):
    times = np.array([4, 4.04, 4.1, 4.18])
    rows = np.column_stack([times, 10+30*(times-4), 20-40*(times-4)])
    status, result = motion_summary(core, rows)
    assert status == 0
    np.testing.assert_allclose(result, [.18, 5.4, -7.2, 50, 4], atol=1e-10)
    # Translation does not change evidence; pixel scaling does, so never call it m/s.
    rows[:, 1:] = rows[:, 1:]*2 + 500
    assert motion_summary(core, rows)[1][3] == pytest.approx(100)


@pytest.mark.parametrize('rows', [
    [[0,0,0],[.03,1,1]],
    [[0,0,0],[.1,1,1],[.4,2,2]],
    [[0,0,0],[0,1,1],[.1,2,2]],
    [[0,0,0],[-.1,1,1],[.1,2,2]],
    [[0,0,0],[.01,1,1],[.02,2,2]],
    [[0,0,0],[.05,float('nan'),1],[.1,2,2]],
])
def test_motion_summary_withholds_missing_or_invalid_evidence(core, rows):
    assert motion_summary(core, rows)[0] == -1


def test_motion_summary_median_limits_single_speed_outlier(core):
    status, result = motion_summary(core, [[0,0,0],[.05,1,0],[.1,2,0],[.15,12,0]])
    assert status == 0
    assert result[3] == pytest.approx(20)
