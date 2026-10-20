"""Standalone desktop app: the editor in its own window.

The packaged app (see packaging/) bundles Python, the libraries, ffmpeg and the pose
model. This module starts the local server on 127.0.0.1 and shows the editor in a native
window (pywebview: WebKit on macOS, Edge WebView2 on Windows). If no native window can be
created it falls back to the default browser.

    ShotTracer               open the editor window
    ShotTracer --browser     use the default browser instead
    ShotTracer --self-test   check the bundle (libraries, ffmpeg, server) and exit
"""

from __future__ import annotations

import argparse
import faulthandler
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser
from pathlib import Path

APP_NAME = "Shot Tracer"


def _bundle_dir() -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))


def _prepare_environment(log: bool) -> None:
    # MediaPipe imports matplotlib; never let it pick a GUI backend inside the app window.
    os.environ.setdefault("MPLBACKEND", "Agg")
    # Bundled ffmpeg first on PATH: video.py finds it like a system install.
    bin_dir = _bundle_dir() / "bin"
    if bin_dir.is_dir():
        os.environ["PATH"] = str(bin_dir) + os.pathsep + os.environ.get("PATH", "")
    # A windowed app has no console (on Windows not even stdout); keep output in a log.
    if log and getattr(sys, "frozen", False):
        path = Path.home() / ".shottracer" / "shottracer.log"
        path.parent.mkdir(parents=True, exist_ok=True)
        sys.stdout = sys.stderr = open(path, "a", buffering=1, encoding="utf-8", errors="replace")  # noqa: SIM115
        print(f"--- {time.strftime('%Y-%m-%d %H:%M:%S')} {APP_NAME} start", flush=True)
    if sys.stderr is not None:
        faulthandler.enable(sys.stderr)  # native crashes (e.g. in a library) leave a traceback


def _free_port(preferred: int) -> int:
    for port in (preferred, 0):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("127.0.0.1", port))
                return s.getsockname()[1]
            except OSError:
                continue
    raise RuntimeError("no free local port")


def _start_server(port: int):
    import uvicorn

    from .server import app

    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", log_config=None))
    threading.Thread(target=server.run, daemon=True).start()
    url = f"http://127.0.0.1:{port}"
    for _ in range(300):  # up to 30 s on a slow first start
        try:
            urllib.request.urlopen(f"{url}/api/watermark", timeout=1).read()
            return server, url
        except OSError:
            time.sleep(0.1)
    raise RuntimeError("the editor server did not start")


class _Api:
    """Called from the page as window.pywebview.api.* (the native window has no download bar)."""

    def __init__(self):
        self._window = None  # private: pywebview exposes public attributes to the page

    def save_output(self, pid: str) -> str | None:
        import webview

        from . import engine

        p = engine.load(pid)
        src = engine.workdir(pid) / f"{Path(p.name).stem}_traced.mp4"
        if not src.exists():
            return None
        picked = self._window.create_file_dialog(
            webview.FileDialog.SAVE, directory=str(Path.home() / "Movies" if sys.platform == "darwin" else Path.home()),
            save_filename=src.name, file_types=("MP4 video (*.mp4)",),
        )
        if not picked:
            return None
        dest = Path(picked if isinstance(picked, str) else picked[0])
        if dest.suffix.lower() != ".mp4":
            dest = dest.with_suffix(".mp4")
        shutil.copyfile(src, dest)
        return str(dest)


def self_test() -> int:
    """Smoke test for packaged builds (CI): imports, ffmpeg, pose model, server."""
    report: dict = {}
    import cv2
    import mediapipe

    from . import person

    report["opencv"] = cv2.__version__
    report["mediapipe"] = getattr(mediapipe, "__version__", "?")
    ffmpeg = shutil.which("ffmpeg")
    report["ffmpeg"] = ffmpeg
    if not ffmpeg:
        raise RuntimeError("bundled ffmpeg not found")
    encoders = subprocess.run([ffmpeg, "-hide_banner", "-encoders"], capture_output=True, text=True).stdout
    if "libx264" not in encoders:
        raise RuntimeError("ffmpeg has no libx264")
    report["model"] = str(person.model_path())
    server, url = _start_server(_free_port(0))
    report["projects"] = len(json.loads(urllib.request.urlopen(f"{url}/api/projects").read()))
    report["editor_page"] = urllib.request.urlopen(url).status
    server.should_exit = True
    print(json.dumps(report, indent=2))
    print("SELF-TEST OK")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="ShotTracer")
    ap.add_argument("--browser", action="store_true", help="open in the default browser instead of a window")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--headless", action="store_true", help="only run the local server (debugging, CI)")
    args, _ = ap.parse_known_args(argv)  # macOS may pass -psn_… when launched from Finder
    _prepare_environment(log=not (args.self_test or args.headless))
    if args.self_test and sys.stdout is None:  # windowed .exe: no console to print to
        sys.stdout = sys.stderr = open(Path.home() / ".shottracer" / "selftest.log", "w", encoding="utf-8")  # noqa: SIM115
    if args.self_test:
        return self_test()

    server, url = _start_server(_free_port(args.port))
    if args.headless:
        print(f"{APP_NAME} server: {url}", flush=True)
        while True:
            time.sleep(3600)
    if not args.browser:
        try:
            import webview

            api = _Api()
            api._window = webview.create_window(APP_NAME, url, js_api=api, width=1440, height=900, min_size=(1000, 640))
            # Not private: language, drawing style and other editor preferences persist.
            webview.start(private_mode=False, storage_path=str(Path.home() / ".shottracer" / "webview"))
            print(f"--- {time.strftime('%Y-%m-%d %H:%M:%S')} window closed, quitting", flush=True)
            server.should_exit = True
            return 0
        except Exception as e:  # no native web view available: use the browser
            print(f"native window unavailable ({e}); opening the browser", file=sys.stderr)
    webbrowser.open(url)
    print(f"{APP_NAME}: {url}  (close this window or press Ctrl+C to quit)")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        server.should_exit = True
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
