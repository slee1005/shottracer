"""Build the standalone app for the current OS.

    uv run --group desktop python packaging/build.py

macOS (Apple silicon) -> dist/ShotTracer-<version>-mac-arm64.dmg
Windows (x64)         -> dist/ShotTracer-<version>-windows-x64.zip

Downloads are pinned by SHA-256: a static ffmpeg build (GPL, shipped as a separate
program with its license) and the MediaPipe pose model, so the app works offline.
"""

from __future__ import annotations

import hashlib
import platform
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD, DIST = ROOT / "build", ROOT / "dist"
VENDOR = BUILD / "vendor"
VERSION = "0.0.1"

FFMPEG_VERSION = "9.0.2"
FFMPEG = {
    "darwin": (  # static arm64 build by Martin Riedl (only macOS system frameworks)
        "https://ffmpeg.martin-riedl.de/download/macos/arm64/1789931890_9.0.2/ffmpeg.zip",
        "c8ed4c4e6978a03c485edbfe4e0a5dc2380f8a30bba5150531b31b094492d924",
    ),
    "win32": (  # "essentials" static build by Gyan Doshi (linked from ffmpeg.org)
        "https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip",
        "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba",
    ),
}
MODEL = (
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/"
    "pose_landmarker_full.task",
    "4eaa5eb7a98365221087693fcc286334cf0858e2eb6e15b506aa4a7ecdcec4ad",
)
FFMPEG_NOTICE = f"""This application includes FFmpeg {FFMPEG_VERSION} (https://ffmpeg.org) as a separate
program (bin/ffmpeg). FFmpeg is free software licensed under the GNU General Public
License version 3 (this build includes libx264). Shot Tracer runs it as an external
process for video encoding; it is not linked into Shot Tracer.

Source code of this FFmpeg version: https://ffmpeg.org/releases/ffmpeg-{FFMPEG_VERSION}.tar.xz
Build used: {{url}}
Full license text: https://www.gnu.org/licenses/gpl-3.0.txt
"""


def fetch(url: str, sha256: str, dest: Path) -> Path:
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == sha256:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"downloading {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "shottracer-build"})
    with urllib.request.urlopen(req) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)
    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    if digest != sha256:
        dest.unlink()
        raise SystemExit(f"checksum mismatch for {url}: {digest}")
    return dest


def vendor() -> None:
    url, sha = FFMPEG[sys.platform]
    archive = fetch(url, sha, VENDOR / "downloads" / Path(url).name)
    exe = "ffmpeg.exe" if sys.platform == "win32" else "ffmpeg"
    (VENDOR / "bin").mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        member = next(n for n in z.namelist() if n.endswith("/" + exe) or n == exe)
        (VENDOR / "bin" / exe).write_bytes(z.read(member))
    (VENDOR / "bin" / exe).chmod(0o755)
    licenses = VENDOR / "licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    (licenses / "FFMPEG-LICENSE.txt").write_text(FFMPEG_NOTICE.format(url=url))
    fetch(*MODEL, VENDOR / "models" / "pose_landmarker_full.task")


def check_platform() -> None:
    machine = platform.machine().lower()
    if sys.platform == "darwin" and machine != "arm64":
        raise SystemExit("macOS builds need Apple silicon (MediaPipe has no Intel-Mac build)")
    if sys.platform == "win32" and machine not in ("amd64", "x86_64"):
        raise SystemExit("Windows builds need x64 (OpenCV has no Windows-on-ARM build)")
    if sys.platform not in FFMPEG:
        raise SystemExit(f"unsupported platform {sys.platform}")


def main() -> int:
    check_platform()
    vendor()
    subprocess.run(
        [sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean",
         "--distpath", str(DIST), "--workpath", str(BUILD / "pyinstaller"),
         str(ROOT / "packaging" / "shottracer.spec")],
        check=True,
    )
    if sys.platform == "darwin":
        app = DIST / "Shot Tracer.app"
        stage = BUILD / "dmg"
        shutil.rmtree(stage, ignore_errors=True)
        stage.mkdir(parents=True)
        subprocess.run(["ditto", str(app), str(stage / app.name)], check=True)
        (stage / "Applications").symlink_to("/Applications")
        out = DIST / f"ShotTracer-{VERSION}-mac-arm64.dmg"
        out.unlink(missing_ok=True)
        subprocess.run(["hdiutil", "create", "-volname", "Shot Tracer", "-srcfolder", str(stage),
                        "-ov", "-format", "UDZO", str(out)], check=True)
        self_test = app / "Contents" / "MacOS" / "ShotTracer"
    else:
        out = DIST / f"ShotTracer-{VERSION}-windows-x64.zip"
        out.unlink(missing_ok=True)
        shutil.make_archive(str(out.with_suffix("")), "zip", DIST, "ShotTracer")
        self_test = DIST / "ShotTracer" / "ShotTracer.exe"
    print(f"built {out} ({out.stat().st_size / 1e6:.0f} MB)")
    result = subprocess.run([str(self_test), "--self-test"])
    log = Path.home() / ".shottracer" / "selftest.log"
    if sys.platform == "win32" and log.exists():
        print(log.read_text())
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
