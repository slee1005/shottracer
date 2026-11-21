import numpy as np
import pytest

from shottracer import engine
from shottracer import trajectory as tj
from shottracer.settings import Project, Trace

from test_trajectory import FPS, START, observe, truth


def _speed(m: tj.Model, t: float, dt: float = 1e-3) -> float:
    return float(np.linalg.norm(m.at(t + dt) - m.at(t)) / dt)


def test_fitted_ball_is_fastest_at_launch():
    m = truth(w=0.0)
    assert _speed(m, 0.01) > _speed(m, m.apex_time()) > 0


@pytest.mark.parametrize("fraction", [0.4, 0.5, 0.6])
def test_apex_time_is_honoured_with_same_landing(fraction):
    m = truth()
    obs = observe(m, range(11, 30))
    T = m.flight_time
    out = tj.with_apex(m, obs, FPS, fraction * T)
    assert out.flight_time == T
    assert out.apex_time() == pytest.approx(fraction * T, abs=0.01)
    assert out.rise(T) == pytest.approx(0.0, abs=1e-6)
    np.testing.assert_allclose(out.at(T), m.at(T), atol=1e-6)  # same landing spot
    t = np.linspace(0.01, T - 0.01, 400)
    assert (out.rise(t) > 0).all()


def test_later_apex_drops_more_steeply():
    m = truth()
    obs = observe(m, range(11, 30))
    T = m.flight_time
    early, late = (tj.with_apex(m, obs, FPS, f * T) for f in (0.4, 0.6))
    slope = lambda mm: (mm.rise(T) - mm.rise(T - 0.01)) / 0.01
    assert slope(late) < slope(early) < 0


def test_apex_outside_the_range_is_clamped():
    m = truth()
    out = tj.with_apex(m, observe(m, range(11, 30)), FPS, 0.95 * m.flight_time)
    assert out.apex_time() == pytest.approx(tj.APEX_FRACTION[1] * m.flight_time, abs=0.01)


def test_old_models_load_without_lift():
    d = truth().to_dict()
    d.pop("lift")
    assert tj.Model.from_dict(d).lift == 0.0


def _flight_project(**shot) -> tuple[Project, Trace]:
    m = truth()
    tr = Trace()
    tr.shot = tr.shot.model_copy(update=shot)
    tr.analysis = {"mode": "flight", "scale": 1.0, "observations": observe(m, range(11, 30)),
                   "base_model": m.to_dict(), "impact": m.t0}
    p = Project(id="t", name="t.mp4", source="t.mp4", info={"fps": FPS, "frames": 400}, traces=[tr])
    return p, tr


def test_edits_flow_into_the_analysis():
    p, tr = _flight_project(flight_time=5.0, apex_time=2.9)
    engine.apply_edits(p, tr)
    path = engine.ShotPath(p, tr)
    assert path.flight == pytest.approx(5.0)
    assert path.flight_apex_time == pytest.approx(2.9, abs=0.02)


def test_manual_path_reaches_its_apex_at_the_set_time(monkeypatch):
    tr = Trace()
    tr.shot.manual = [(0.0, 400.0), (100.0, 100.0), (300.0, 50.0), (500.0, 200.0), (600.0, 400.0)]
    tr.shot.flight_time, tr.shot.apex_time = 4.0, 2.4
    tr.analysis = {"mode": "manual", "scale": 1.0, "observations": [], "impact": 0.0}
    p = Project(id="t", name="t.mp4", source="t.mp4", info={"fps": FPS, "frames": 200}, traces=[tr])
    monkeypatch.setattr(engine, "transforms", lambda p, tr: [np.eye(3)] * 200)
    path = engine.ShotPath(p, tr)
    assert path.flight_apex_time == pytest.approx(2.4, abs=0.02)


def test_timeline_retimes_flight_without_rewriting_auto_fit():
    p, tr = _flight_project(flight_time=4.0)
    engine.apply_edits(p, tr)
    original_fit = tr.analysis["base_model"].copy()
    original = engine.ShotPath(p, tr)
    landing_frame = original.t0 + original.flight * FPS
    engine.retime_shot(p, tr, "start", int(original.t0) + 6)
    engine.apply_edits(p, tr)
    shifted = engine.ShotPath(p, tr)
    assert shifted.t0 == int(original.t0) + 6
    assert shifted.t0 + shifted.flight * FPS == pytest.approx(landing_frame)
    apex_frame = round(shifted.t0 + .55 * shifted.flight * FPS)
    engine.retime_shot(p, tr, "apex", apex_frame)
    engine.apply_edits(p, tr)
    edited = engine.ShotPath(p, tr)
    assert edited.t0 + edited.flight_apex_time * FPS == pytest.approx(apex_frame, abs=1e-4)
    np.testing.assert_allclose(edited.points[-1], original.points[-1], atol=1e-5)
    assert tr.analysis["base_model"] == original_fit
    engine.retime_shot(p, tr, "landing", round(landing_frame) + 9)
    engine.apply_edits(p, tr)
    edited = engine.ShotPath(p, tr)
    assert edited.t0 == shifted.t0
    assert edited.t0 + edited.flight * FPS == pytest.approx(round(landing_frame) + 9)
    np.testing.assert_allclose(edited.points[-1], original.points[-1], atol=1e-5)


def test_timeline_retimes_putt_without_changing_its_ground_points():
    tr = Trace()
    tr.shot.mode = "putt"
    observations = [(10, 0, 0), (20, 10, 10), (40, 35, 40)]
    tr.analysis = {"mode": "putt", "observations": observations, "impact": 10.0}
    p = Project(id="putt", name="p.mp4", source="p.mp4", info={"fps": 10, "frames": 100}, traces=[tr])
    original = engine.ShotPath(p, tr)
    engine.retime_shot(p, tr, "landing", 60)
    path = engine.ShotPath(p, tr)
    assert path.t0 == 10
    assert path.flight == 5
    np.testing.assert_array_equal(path.points, original.points)
    assert tr.analysis["observations"] == observations
    with pytest.raises(ValueError):
        engine.retime_shot(p, tr, "apex", 30)
