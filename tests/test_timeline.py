from __future__ import annotations

import copy

import numpy as np
import pytest

from shottracer import engine, render, video
from shottracer.settings import Project, Shot, Trace


def test_manual_timing_round_trip_and_selected_shot_only(tmp_path, monkeypatch):
    monkeypatch.setattr(engine, "WORKSPACE", tmp_path)
    selected = Trace(shot=Shot(manual=[(10, 90), (50, 10), (90, 90)], manual_frame=15, flight_time=2))
    other = Trace(shot=Shot(ball=(20, 30), ball_frame=9))
    project = Project(id="timeline", name="test.mp4", source="test.mp4",
                      info={"fps": 30, "frames": 120, "scale": 1, "work_width": 100}, traces=[selected, other])
    engine.sync_manual(project, selected)
    before = copy.deepcopy(selected.analysis)
    other_before = other.model_dump()
    engine.retime_shot(project, selected, "start", 30)
    engine.retime_shot(project, selected, "landing", 90)
    engine.save(project)
    restored = engine.load(project.id)
    path = engine.ShotPath(restored, restored.traces[0])
    assert path.t0 == 30
    assert path.t0 + path.flight * 30 == 90
    np.testing.assert_allclose(path.points[[0, -1]], [[10, 90], [90, 90]])
    assert restored.traces[0].analysis == before
    assert restored.traces[1].model_dump() == other_before
    assert Shot.model_validate({"ball_frame": 9}).timeline_start is None


def test_timeline_bounds_and_slow_motion_follow_edited_impact():
    trace = Trace(shot=Shot(mode="putt"), analysis={"mode": "putt", "impact": 10,
                  "observations": [(10, 0, 0), (40, 20, 10)]})
    project = Project(id="putt", name="putt", source="putt", info={"fps": 10, "frames": 100}, traces=[trace])
    engine.retime_shot(project, trace, "start", 1000)
    path = engine.ShotPath(project, trace)
    assert path.t0 == 38  # Keep at least 0.2 seconds before the existing landing.
    assert path.t0 + path.flight * 10 == pytest.approx(40)
    engine.retime_shot(project, trace, "landing", -1000)
    assert engine.ShotPath(project, trace).flight == pytest.approx(.2)
    engine.retime_shot(project, trace, "landing", 1000)
    assert engine.ShotPath(project, trace).t0 + trace.shot.flight_time * 10 == 99
    project.slowmo.enabled = True
    project.slowmo.before = project.slowmo.after = .1
    fractional = [frame for frame in render.timeline(project) if frame != int(frame)]
    assert fractional and all(37 <= frame < 39 for frame in fractional)


@pytest.mark.parametrize("times, expected", [
    ([1000, 1033, 1080, 1100], [0, .033, .08, .1]),  # Variable-rate presentation timestamps.
    ([0, 0, 0], []), ([0, 20, 10], []), ([0, float("nan")], []), ([], []),
])
def test_source_time_normalization_and_fps_fallback(times, expected):
    assert video._normalize_times(times) == expected
