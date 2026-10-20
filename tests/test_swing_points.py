import numpy as np

from shottracer import swing
from shottracer.settings import Shot


def test_curve_passes_through_points_in_time_order():
    pts = [(10, 0.0, 0.0), (12, 50.0, -40.0), (13, 120.0, -20.0), (20, 200.0, 100.0)]
    d = swing.through_points(pts, per=16)
    assert np.all(np.diff(d[:, 0]) >= 0)
    for f, x, y in pts:
        row = d[np.argmin(np.abs(d[:, 0] - f))]
        assert np.hypot(row[1] - x, row[2] - y) < 1e-6


def test_uneven_spacing_does_not_overshoot():
    # A big jump next to small steps (fast club near impact): the centripetal curve stays
    # inside the bounding box of the points instead of looping past them.
    pts = [(0, 0.0, 0.0), (1, 2.0, 0.0), (2, 200.0, 0.0), (3, 202.0, 0.0)]
    d = swing.through_points(pts)
    assert d[:, 1].min() > -1.0 and d[:, 1].max() < 203.0


def test_one_point_per_frame_sorted():
    assert Shot(swing_points=[(5, 1, 1), (3, 2, 2), (5.4, 9, 9)]).swing_points == [(3.0, 2.0, 2.0), (5.0, 9.0, 9.0)]
