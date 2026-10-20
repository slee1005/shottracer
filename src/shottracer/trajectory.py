"""Launch detection, track linking and a perspective ballistic model of the ball flight.

Model (reference-frame image coordinates, t = seconds since impact):

    s(t) = (1 - exp(-k t)) / k                         # drag-slowed travel
    u(t) = u0 + (a s + c t^2) / (1 + w s)              # lateral: launch direction + curve
    v(t) = vh + (v0 - vh - (b s - g t^2)) / (1 + w s)  # height: launch speed - gravity

`w` captures the ball moving away from (w > 0) the camera, which shrinks image motion
over time and pulls the ground track toward the horizon row `vh`. For fixed (w, k, t0)
the model is linear in (a, c) and (b, g), so fitting is a small grid search plus least
squares."""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from .ball import Candidate

DRAG_K = 0.35
CURVE_RIDGE = 1.0
W_GRID = np.concatenate([[0.0], np.geomspace(0.05, 40.0, 60)])


# Gravity is only identifiable once the track covers a good part of the flight; an
# early-ascent track fits many flight times equally well.
MIN_OBSERVED_FRACTION = 0.35


@dataclass(frozen=True)
class FlightLimits:
    """Plausible flight time window and the typical value used when the observed
    track is too short to determine it (seconds)."""

    min_time: float
    max_time: float
    typical: float | None = None

    def __post_init__(self):
        if self.typical is None:
            object.__setattr__(self, "typical", (self.min_time + self.max_time) / 2)


SHOT_TYPES = {
    "chip": FlightLimits(0.8, 2.5, 1.5),
    "pitch": FlightLimits(2.0, 4.5, 3.2),
    "full": FlightLimits(3.5, 7.5, 5.5),
    "drive": FlightLimits(5.0, 8.5, 6.5),
}


@dataclass
class Model:
    u0: float
    v0: float
    vh: float  # horizon row (vanishing line of the ground plane)
    t0: float  # impact time in frames (fractional)
    a: float
    c: float
    b: float
    g: float
    w: float
    k: float
    flight_time: float  # seconds
    height: float = 1.0  # user height scale applied on top of the fitted flight

    def at(self, t: np.ndarray | float) -> np.ndarray:
        t = np.asarray(t, dtype=np.float64)
        s = _s(t, self.k)
        den = 1.0 + self.w * s
        u = self.u0 + (self.a * s + self.c * t * t) / den
        v = self.vh + (self.v0 - self.vh - self.height * (self.b * s - self.g * t * t)) / den
        return np.stack([u, v], axis=-1)

    def to_dict(self) -> dict:
        return {k: float(v) for k, v in self.__dict__.items()}

    @classmethod
    def from_dict(cls, d: dict) -> "Model":
        return cls(**d)


class AnalysisError(ValueError):
    """Expected analysis failure with a user-facing explanation."""


def _s(t, k):
    return (1.0 - np.exp(-k * np.asarray(t))) / k


def _landing_time(b: float, g: float, k: float) -> float:
    """Smallest t > 0 with b s(t) = g t^2 (height back to zero)."""
    if g <= 1e-9 or b <= 0:
        return math.inf
    lo, hi = 1e-3, 30.0
    f = lambda t: b * _s(t, k) - g * t * t
    if f(hi) > 0:
        return math.inf
    for _ in range(60):
        mid = (lo + hi) / 2
        if f(mid) > 0:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def detect_launch(
    cands: dict[int, list[Candidate]],
    start: tuple[float, float],
    first_frame: int,
    last_frame: int,
    max_jump: float,
    min_speed: float,
    min_strength: float = 25.0,
    gone=None,
) -> tuple[int, list[Candidate]] | None:
    """Earliest frame where three consecutive candidates leave `start` along a consistent
    upward path. A struck ball is fastest right after impact and then only slows down in
    the image, which separates it from accelerating club/hand motion; `gone` rejects
    motion while the ball is still at address (e.g. a decelerating late backswing).
    Returns (first in-flight frame, the three candidates)."""
    sx, sy = start

    def ok_step(prev_v: np.ndarray, v: np.ndarray, lo: float, hi: float) -> bool:
        n0, n1 = np.linalg.norm(prev_v), np.linalg.norm(v)
        if n1 < 1.0 or not (lo * n0 <= n1 <= hi * n0):
            return False
        cos = float(prev_v @ v) / (n0 * n1)
        return cos > math.cos(math.radians(30))

    # The first in-flight frame shows the ball closest and most contrasted; later
    # frames fade as it recedes, so they only need a fraction of that contrast.
    strong = {t: [c for c in cs if c.strength >= min_strength] for t, cs in cands.items()}
    cands = {t: [c for c in cs if c.strength >= 0.6 * min_strength] for t, cs in cands.items()}
    for t in range(first_frame, last_frame - 2):
        # `gone(t)`: the address spot no longer shows the ball (it has really left).
        if gone is not None and not all(gone(t + k) for k in (0, 2, 4)):
            continue
        best = None
        for c1 in strong.get(t, []):
            v1 = np.array([c1.x - sx, c1.y - sy])
            d1 = np.linalg.norm(v1)
            if not (2.0 <= d1 <= max_jump) or v1[1] > -0.3 * d1:  # must head upward
                continue
            for c2 in cands.get(t + 1, []):
                v2 = np.array([c2.x - c1.x, c2.y - c1.y])
                # c1 may be less than one full frame of travel after impact.
                if np.linalg.norm(v2) < min_speed or not ok_step(v1, v2, 0.35, 2.5):
                    continue
                for c3 in cands.get(t + 2, []):
                    v3 = np.array([c3.x - c2.x, c3.y - c2.y])
                    if not ok_step(v2, v3, 0.3, 1.1):
                        continue
                    score = c1.strength + c2.strength + c3.strength
                    if best is None or score > best[0]:
                        best = (score, [c1, c2, c3])
        if best is not None:
            return t, best[1]
    return None


def fit(
    points: list[tuple[float, float, float]],
    start: tuple[float, float],
    fps: float,
    t0_range: tuple[float, float],
    limits: FlightLimits,
    horizon: float,
    landing: tuple[float, float] | None = None,
    flight_time: float | None = None,
) -> Model:
    """Fit the model to (frame, x, y) observations."""
    best: tuple[float, Model] | None = None
    for t0 in np.linspace(t0_range[0], t0_range[1], 11):
        for w in W_GRID:
            r = _solve(points, start, fps, float(t0), float(w), limits, horizon, landing, flight_time)
            if r is not None and (best is None or r[0] < best[0]):
                best = r
    if best is None:
        raise AnalysisError("not enough ball observations after impact to fit a trajectory")
    return best[1]


def refit(
    model: Model,
    points: list[tuple[float, float, float]],
    fps: float,
    limits: FlightLimits,
    landing: tuple[float, float] | None = None,
    flight_time: float | None = None,
    height: float = 1.0,
) -> Model:
    """Re-apply user edits keeping the fitted impact time and perspective (fast)."""
    r = _solve(points, (model.u0, model.v0), fps, model.t0, model.w, limits, model.vh, landing, flight_time)
    if r is None:
        raise AnalysisError("cannot refit trajectory")
    m = r[1]
    m.height = height
    return m


def _solve(points, start, fps, t0, w, limits, horizon, landing, flight_time) -> tuple[float, Model] | None:
    p = np.asarray(points, dtype=np.float64)
    frames, us, vs = p[:, 0], p[:, 1], p[:, 2]
    u0, v0 = start
    t = (frames - t0) / fps
    if (t <= 0).any():
        return None
    s = _s(t, DRAG_K)
    den = 1.0 + w * s
    A = np.stack([s / den, t * t / den], axis=1)
    # Ridge on the curve term: a short early track cannot tell curve from
    # perspective, so only bend the flight when the data clearly demands it.
    ridge = np.array([[0.0, math.sqrt(CURVE_RIDGE * float(A[:, 1] @ A[:, 1]))]])
    (a, c), *_ = np.linalg.lstsq(np.vstack([A, ridge]), np.append(us - u0, 0.0), rcond=None)
    rhs = (v0 - horizon) / den + horizon - vs
    (b, g), *_ = np.linalg.lstsq(np.stack([s / den, -t * t / den], axis=1), rhs, rcond=None)
    m = Model(u0, v0, horizon, t0, float(a), float(c), float(b), max(float(g), 0.0), w, DRAG_K, 0.0)
    m = _constrain(m, t, us, vs, limits, landing, flight_time)
    r = m.at(t) - np.stack([us, vs], axis=1)
    return float((r * r).sum()), m


def _constrain(
    m: Model, t: np.ndarray, us: np.ndarray, vs: np.ndarray, limits: FlightLimits, landing, flight_time=None
) -> Model:
    """Apply the flight-time prior, an explicit flight time and/or landing point."""
    s = _s(t, m.k)
    den = 1.0 + m.w * s
    if landing is not None:
        # Choose the flight time whose landing point best matches the user's point
        # (or use the explicit one), then bend laterally to hit the landing x exactly.
        times = [flight_time] if flight_time else np.linspace(limits.min_time, limits.max_time, 41)
        best = None
        for T in times:
            mm = _tie_gravity(m, float(T), s, t, den, vs)
            sT = _s(T, m.k)
            denT = 1.0 + mm.w * sT
            mm.c = float(((landing[0] - mm.u0) * denT - mm.a * sT) / (T * T))
            e = abs(mm.at(T)[1] - landing[1])
            if best is None or e < best[0]:
                best = (e, mm)
        return best[1]
    if flight_time:
        return _tie_gravity(m, float(flight_time), s, t, den, vs)
    T = _landing_time(m.b, m.g, m.k)
    if float(t.max()) < MIN_OBSERVED_FRACTION * min(T, limits.max_time):
        return _tie_gravity(m, limits.typical, s, t, den, vs)
    if limits.min_time <= T <= limits.max_time:
        m.flight_time = T
        return m
    T = float(np.clip(T, limits.min_time, limits.max_time))
    return _tie_gravity(m, T, s, t, den, vs)


def _tie_gravity(m: Model, T: float, s, t, den, vs) -> Model:
    """Refit b with g = b s(T) / T^2 so the ball lands exactly at time T."""
    sT = _s(T, m.k)
    basis = (s - sT * t * t / (T * T)) / den
    rhs = (m.v0 - m.vh) / den + m.vh - vs
    b = float(basis @ rhs / max(basis @ basis, 1e-12))
    return Model(m.u0, m.v0, m.vh, m.t0, m.a, m.c, b, b * sT / (T * T), m.w, m.k, T)


def track(
    cands: dict[int, list[Candidate]],
    seed: list[Candidate],
    start: tuple[float, float],
    launch_frame: int,
    fps: float,
    limits: FlightLimits,
    horizon: float,
    last_frame: int,
    max_misses: int,
) -> list[Candidate]:
    """Extend the seed track frame by frame, gating candidates by the current model."""
    found = list(seed)
    misses = 0
    t = seed[-1].frame + 1
    while t <= last_frame and misses < max_misses:
        m = fit([(c.frame, c.x, c.y) for c in found], start, fps, (launch_frame - 1, launch_frame - 0.05), limits, horizon)
        pred = m.at((t - m.t0) / fps)
        step = np.linalg.norm(m.at((t - m.t0) / fps) - m.at((t - 1 - m.t0) / fps))
        gate = max(5.0, 0.6 * step) + 1.5 * misses
        options = [c for c in cands.get(t, []) if math.hypot(c.x - pred[0], c.y - pred[1]) <= gate]
        if options:
            found.append(min(options, key=lambda c: math.hypot(c.x - pred[0], c.y - pred[1]) - 0.05 * c.strength))
            misses = 0
        else:
            misses += 1
        t += 1
    return found
