import numpy as np

from shottracer import camera, putt


def synthetic_putt(n=115, impact=20, fps=30.0):
    """Static camera, textured green, white ball rolling right and slowing down."""
    rng = np.random.default_rng(1)
    green = (90 + rng.normal(0, 6, (240, 400))).clip(0, 255).astype(np.uint8)
    frames, truth = [], []
    x0, y0, v0, decel = 60.0, 150.0, 7.0, 0.1  # stops at x≈305, frame 90
    for i in range(n):
        t = max(0, i - impact)
        v_t = max(0.0, v0 - decel * t)
        dist = (v0 + v_t) / 2 * min(t, v0 / decel)
        x = x0 + dist
        y = y0 - 0.08 * dist  # slight break uphill in the image
        img = green.copy()
        yy, xx = np.ogrid[:240, :400]
        img[(xx - x) ** 2 + (yy - y) ** 2 <= 16] = 235
        frames.append(img)
        truth.append((x, y))
    return frames, truth


def test_putt_track_follows_rolling_ball():
    frames, truth = synthetic_putt()
    transforms = camera.estimate(frames, reference=5)
    impact, path = putt.track(frames, transforms, 5, truth[5], radius=4.0, fps=30.0)
    assert 18 <= impact <= 21
    errors = [np.hypot(x - truth[f][0], y - truth[f][1]) for f, x, y in path[1:]]
    assert max(errors) < 2.5
    # Followed until the ball stopped.
    assert path[-1][0] >= 90
    assert abs(path[-1][1] - truth[-1][0]) < 2
