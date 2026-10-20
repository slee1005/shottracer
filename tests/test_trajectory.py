import numpy as np
import pytest

from shottracer import trajectory as tj
from shottracer.ball import Candidate

FPS = 30.0
START = (480.0, 500.0)
HORIZON = 290.0


def truth(**kw) -> tj.Model:
    params = dict(u0=START[0], v0=START[1], vh=HORIZON, t0=10.0, a=600.0, c=0.0,
                  b=3000.0, g=300.0, w=8.0, k=tj.DRAG_K, flight_time=0.0)
    params.update(kw)
    m = tj.Model(**params)
    m.flight_time = tj._landing_time(m.b, m.g, m.k)
    return m


def observe(m: tj.Model, frames, noise=0.0, seed=0):
    rng = np.random.default_rng(seed)
    pts = m.at((np.asarray(frames, float) - m.t0) / FPS)
    pts = pts + rng.normal(0, noise, pts.shape)
    return [(f, x, y) for f, (x, y) in zip(frames, pts)]


def _assert_follows(fit, m, frames, tol=3.0):
    for f in frames:
        t_true = (f - m.t0) / FPS
        t_fit = (f - fit.t0) / FPS
        assert np.linalg.norm(fit.at(t_fit) - m.at(t_true)) < tol


def test_short_early_track_uses_typical_flight_time():
    # 0.27 s of ascent cannot determine gravity; the shot-type prior decides.
    m = truth()
    obs = observe(m, range(11, 19), noise=0.5)
    limits = tj.FlightLimits(2.0, 9.0, typical=5.0)
    fit = tj.fit(obs, START, FPS, (10.0, 10.95), limits, HORIZON)
    _assert_follows(fit, m, range(11, 19))
    assert fit.flight_time == pytest.approx(5.0)


def test_long_track_recovers_flight_time():
    m = truth()
    frames = range(11, 11 + int(0.5 * m.flight_time * FPS))
    fit = tj.fit(observe(m, frames, noise=0.5), START, FPS, (10.0, 10.95), tj.FlightLimits(2.0, 9.0), HORIZON)
    _assert_follows(fit, m, frames)
    assert fit.flight_time == pytest.approx(m.flight_time, rel=0.1)


def test_flight_time_stays_within_shot_limits():
    obs = observe(truth(), range(11, 17))
    fit = tj.fit(obs, START, FPS, (10.0, 10.95), tj.FlightLimits(1.0, 1.5), HORIZON)
    assert 1.0 <= fit.flight_time <= 1.5


def test_landing_point_is_honoured():
    obs = observe(truth(), range(11, 17))
    landing = (620.0, 300.0)
    fit = tj.fit(obs, START, FPS, (10.0, 10.95), tj.SHOT_TYPES["full"], HORIZON, landing)
    land = fit.at(fit.flight_time)
    assert land[0] == pytest.approx(landing[0], abs=1.0)
    assert land[1] == pytest.approx(landing[1], abs=15.0)


def _cands(path, strength=80.0):
    return {f: [Candidate(f, x, y, 10.0, strength)] for f, x, y in path}


def test_launch_detected_at_first_flight_frame():
    m = truth()
    cands = _cands(observe(m, range(11, 20)))
    found = tj.detect_launch(cands, START, 1, 25, max_jump=240, min_speed=19)
    assert found is not None and found[0] == 11


def test_accelerating_takeaway_is_not_a_launch():
    # Club/hand motion speeds up away from the ball: rejected even with strong contrast.
    takeaway = [(3, 470, 490), (4, 455, 470), (5, 430, 440), (6, 395, 400)]
    m = truth()
    cands = _cands(takeaway)
    for f, cs in _cands(observe(m, range(11, 20))).items():
        cands.setdefault(f, []).extend(cs)
    found = tj.detect_launch(cands, START, 1, 25, max_jump=240, min_speed=19)
    assert found is not None and found[0] == 11


def test_weak_candidates_cannot_start_a_launch():
    cands = _cands(observe(truth(), range(11, 20)), strength=10.0)
    assert tj.detect_launch(cands, START, 1, 25, max_jump=240, min_speed=19) is None


def test_launch_requires_ball_to_have_left_address():
    # Same clean path, but the address spot still shows the ball until frame 14:
    # motion before that cannot be the launch.
    m = truth()
    cands = _cands(observe(m, range(11, 20)))
    found = tj.detect_launch(cands, START, 1, 25, max_jump=240, min_speed=19, gone=lambda t: t >= 14)
    assert found is None or found[0] >= 14
    found = tj.detect_launch(cands, START, 1, 25, max_jump=240, min_speed=19, gone=lambda t: t >= 11)
    assert found is not None and found[0] == 11
