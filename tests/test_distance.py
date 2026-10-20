import pytest

from shottracer import engine
from shottracer.settings import Popup, Project, Shot, Trace


@pytest.mark.parametrize(
    "text, yards",
    [("245 yd", 245), ("230yds", 230), ("210 야드", 210), ("200m", 218.72), ("150 meters", 164.04), ("180", 180), ("", None), ("far", None)],
)
def test_parse_distance(text, yards):
    got = engine.parse_distance(text)
    assert got == pytest.approx(yards, abs=0.01) if yards else got is None


def project(*popups, **shot):
    return Trace(shot=Shot(**shot), popups=list(popups))


def test_distance_sets_landing_time():
    short = engine.distance_time(project(Popup(kind="distance", value="50 yd")))
    long = engine.distance_time(project(Popup(kind="distance", value="280 yd")))
    assert short < long
    # carry is where the ball lands, so it wins over total distance
    both = project(Popup(kind="distance", value="280 yd"), Popup(kind="carry", value="50 yd"))
    assert engine.distance_time(both) == short


def test_explicit_time_and_putt_override_distance():
    pop = Popup(kind="distance", value="250 yd")
    assert engine.flight_time(project(pop, flight_time=3.0)) == 3.0
    assert engine.flight_time(project(pop, mode="putt")) is None
    assert engine.flight_time(project(Popup(kind="speed", value="150 mph"))) is None


def test_single_shot_projects_migrate_to_first_trace():
    old = {"id": "x", "name": "x", "source": "x", "status": "ready", "shot": {"ball": [1, 2]},
           "analysis": {"mode": "putt"}, "popups": [{"kind": "text", "value": "hi"}]}
    p = Project.model_validate(old)
    assert len(p.traces) == 1 and p.traces[0].shot.ball == (1, 2)
    assert p.traces[0].analysis == {"mode": "putt"} and p.traces[0].popups[0].value == "hi"
    assert p.traces[0].status == "ready"
