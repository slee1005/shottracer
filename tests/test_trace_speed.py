import pytest

from shottracer import engine
from shottracer.settings import Project, Trace


def _putt_project(speed: float) -> tuple[Project, Trace]:
    # Ball observed over 60 frames at 30 fps: a 2 s roll after impact (frame 10).
    obs = [(10.0 + i, 100.0 + 2 * i, 200.0) for i in range(61)]
    tr = Trace()
    tr.shot.mode = "putt"
    tr.shot.speed = speed
    tr.analysis = {"mode": "putt", "impact": 10.0, "observations": obs, "scale": 1.0}
    p = Project(id="t", name="t.mp4", source="t.mp4", info={"fps": 30.0, "frames": 100}, traces=[tr])
    return p, tr


def test_default_speed_follows_the_ball():
    p, tr = _putt_project(1.0)
    path = engine.ShotPath(p, tr)
    assert path.end == pytest.approx(2.0)
    assert path.flight == pytest.approx(2.0)


def test_faster_tracer_draws_in_flight_time_over_speed():
    p, tr = _putt_project(2.0)
    path = engine.ShotPath(p, tr)
    assert path.end == pytest.approx(1.0)  # line lands after half the roll
    assert path.flight == pytest.approx(2.0)  # measured time unchanged
    # Same geometry, reached earlier: halfway along the drawing timeline is the end point.
    assert path.point_at(1.0)[0] == pytest.approx(220.0)
    assert path.point_at(0.5)[0] == pytest.approx(160.0)


def test_speed_is_bounded():
    with pytest.raises(ValueError):
        Trace.model_validate({"shot": {"speed": 10}})
