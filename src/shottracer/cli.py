"""Command line entry points.

    shottracer INPUT --ball X,Y [...]   analyse + render in one go
    shottracer --project ID [-o OUT]    render a project prepared in the web UI
    shottracer-ui                       start the web editor
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from . import engine, render, trajectory
from .trajectory import AnalysisError


def _point(text: str) -> tuple[float, float]:
    try:
        x, y = (float(v) for v in text.split(","))
    except ValueError:
        raise argparse.ArgumentTypeError("expected X,Y") from None
    return x, y


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="shottracer",
        description="Draw the golf ball's flight (or putt) onto a video. "
        "Use `shottracer-ui` for the interactive editor.",
    )
    p.add_argument("input", type=Path, nargs="?")
    p.add_argument("-o", "--output", type=Path, help="output video (default: INPUT_traced.mp4)")
    p.add_argument("--project", help="render an existing project (id shown in the web UI)")
    p.add_argument("--ball", type=_point, help="ball position X,Y in video pixels")
    p.add_argument("--ball-frame", type=int, default=0, help="frame where --ball was measured (default 0)")
    p.add_argument("--putt", action="store_true", help="track a rolling ball (putt/chip) instead of a flight")
    p.add_argument("--landing", type=_point, help="landing spot X,Y in video pixels on --ball-frame")
    p.add_argument("--impact", type=int, help="impact frame, if automatic detection fails")
    p.add_argument("--horizon", type=float, help="horizon row in video pixels (default: estimated)")
    p.add_argument("--shot", choices=sorted(trajectory.SHOT_TYPES), default="full",
                   help="shot type, bounds the flight time (default: full)")
    p.add_argument("--flight-time", type=float, help="exact flight time in seconds")
    p.add_argument("--style", choices=sorted(render.PRESETS), default="classic")
    p.add_argument("--color", default="#FF2D2D", help="tracer color RRGGBB (default FF2D2D)")
    p.add_argument("--line-width", type=float, default=1.0, help="tracer thickness multiplier")
    p.add_argument("--no-cutout", action="store_true", help="draw the tracer over the golfer")
    p.add_argument("--swing", choices=["clubhead", "hands"], help="also draw the swing path")
    p.add_argument("--slowmo", type=float, help="slow motion factor around impact (drops audio)")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return _run(args)
    except (AnalysisError, ValueError, KeyError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


def _run(args) -> int:
    t_start = time.monotonic()
    if args.project:
        p = engine.load(args.project)
        out = args.output or engine.source_path(p).with_name(f"{Path(p.name).stem}_traced.mp4")
        render.export(p, out, _bar)
        print(f"\nwrote {out} in {time.monotonic() - t_start:.1f}s", file=sys.stderr)
        return 0
    if args.input is None or args.ball is None:
        raise ValueError("give INPUT and --ball X,Y (or use shottracer-ui to pick the ball)")
    src: Path = args.input
    if not src.exists():
        raise ValueError(f"no such file: {src}")
    out: Path = args.output or src.with_name(f"{src.stem}_traced.mp4")

    print("preparing…", file=sys.stderr)
    p = engine.create(src, copy=False)
    s = p.shot
    s.mode = "putt" if args.putt else "flight"
    s.ball, s.ball_frame, s.landing = args.ball, args.ball_frame, args.landing
    s.impact, s.horizon, s.shot, s.flight_time = args.impact, args.horizon, args.shot, args.flight_time
    p.style.preset, p.style.color, p.style.width = args.style, args.color, args.line_width
    p.style.cutout = not args.no_cutout
    if args.swing:
        p.swing.enabled, p.swing.source = True, args.swing
    if args.slowmo:
        p.slowmo.enabled, p.slowmo.factor = True, args.slowmo

    engine.analyze(p, lambda f, msg="": print(f"\ranalysing {f:4.0%} {msg:<20}", end="", file=sys.stderr))
    engine.save(p)
    path = engine.ShotPath(p)
    a = p.analysis
    print(
        f"\nimpact ≈ frame {a['impact']:.1f}, ball tracked in {len(a['observations'])} frames, "
        f"{'flight' if path.mode == 'flight' else 'roll'} {path.end:.1f}s  (project {p.id})",
        file=sys.stderr,
    )
    render.export(p, out, _bar)
    print(f"\nwrote {out} in {time.monotonic() - t_start:.1f}s", file=sys.stderr)
    return 0


def _bar(f: float) -> None:
    print(f"\rrendering {f:4.0%}", end="", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
