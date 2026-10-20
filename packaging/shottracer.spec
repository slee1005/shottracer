# PyInstaller spec for the standalone app. Run through packaging/build.py, which first
# downloads the pinned ffmpeg and pose model into build/vendor.
import sys
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files, collect_dynamic_libs, collect_submodules

ROOT = Path(SPECPATH).parent
VENDOR = ROOT / "build" / "vendor"
WINDOWS, MAC = sys.platform == "win32", sys.platform == "darwin"
FFMPEG = "ffmpeg.exe" if WINDOWS else "ffmpeg"

datas = [
    (str(ROOT / "src" / "shottracer" / "web"), "shottracer/web"),
    (str(VENDOR / "models" / "pose_landmarker_full.task"), "models"),
    (str(VENDOR / "licenses"), "licenses"),
]
datas += collect_data_files("mediapipe")
binaries = [(str(VENDOR / "bin" / FFMPEG), "bin")]
binaries += collect_dynamic_libs("mediapipe")
hiddenimports = collect_submodules("uvicorn") + collect_submodules("shottracer") + ["python_multipart"]

a = Analysis(
    [str(ROOT / "packaging" / "launcher.py")],
    pathex=[str(ROOT / "src")],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    excludes=["pytest", "tkinter", "IPython"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, [],
    exclude_binaries=True,
    name="ShotTracer",
    console=False,  # windowed app; output goes to ~/.shottracer/shottracer.log
    icon=str(ROOT / "packaging" / ("icon.ico" if WINDOWS else "icon.icns")),
    codesign_identity=None,
    entitlements_file=None,
)
coll = COLLECT(exe, a.binaries, a.datas, name="ShotTracer")
if MAC:
    app = BUNDLE(
        coll,
        name="Shot Tracer.app",
        icon=str(ROOT / "packaging" / "icon.icns"),
        bundle_identifier="com.ajc.shottracer",
        info_plist={
            "CFBundleDisplayName": "Shot Tracer",
            "CFBundleShortVersionString": "0.0.1",
            "NSHighResolutionCapable": True,
            "LSMinimumSystemVersion": "12.0",
            "NSHumanReadableCopyright": "Create by AJC_shottracer",
        },
    )
