"""Sign and package the Apple silicon editor for the Mac App Store."""

from __future__ import annotations

import argparse
import hashlib
import plistlib
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path

BUNDLE_ID = "com.johnajc.shottracer"
APP_NAME = "AJCShotTracer"
QUARANTINE = "com.apple.quarantine"
MACH_O = {b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf", b"\xca\xfe\xba\xbe"}
SANDBOX = "com.apple.security.app-sandbox"


def add_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--mac-app-store", action="store_true", help="build a signed Mac App Store PKG")
    parser.add_argument("--build-number", type=int)
    parser.add_argument("--profile", type=Path, help="Mac App Store Connect provisioning profile")
    parser.add_argument("--app-identity", help="Apple Distribution signing identity")
    parser.add_argument("--installer-identity", help="Mac Installer Distribution signing identity")


def profile_application_id(path: Path) -> str:
    """Use only an unexpired macOS store profile for this app and its team."""
    data = plistlib.loads(subprocess.check_output(["security", "cms", "-D", "-i", path]))
    entitlements = data.get("Entitlements", {})
    team = entitlements.get("com.apple.developer.team-identifier")
    application_id = entitlements.get("com.apple.application-identifier")
    if (
        "OSX" not in data.get("Platform", [])
        or "ProvisionedDevices" in data
        or data.get("ProvisionsAllDevices")
        or entitlements.get("com.apple.security.get-task-allow")
        or not team
        or application_id != f"{team}.{BUNDLE_ID}"
        or data["ExpirationDate"].replace(tzinfo=timezone.utc) <= datetime.now(timezone.utc)
    ):
        raise ValueError(f"Expected a current Mac App Store profile for {BUNDLE_ID}")
    return application_id


def run(*args: str | Path) -> None:
    subprocess.run(args, check=True)


def has_quarantine(app: Path) -> bool:
    # List names only. With -s, inspect symlinks without following them.
    attributes = subprocess.check_output(["xattr", "-rs", app], text=True)
    return any(line == QUARANTINE or line.endswith(f": {QUARANTINE}") for line in attributes.splitlines())


def verify_no_quarantine(app: Path) -> None:
    if has_quarantine(app):
        raise RuntimeError(f"{QUARANTINE} remains in {app}")


def sign_bundle(app: Path, profile: Path, application_id: str, identity: str) -> None:
    contents = app / "Contents"
    # Write the profile bytes without inheriting Chrome's download metadata.
    embedded = contents / "embedded.provisionprofile"
    embedded.write_bytes(profile.read_bytes())
    manifest = Path(__file__).with_name("PrivacyInfo.xcprivacy")
    shutil.copyfile(manifest, contents / "Resources" / manifest.name)

    # Apple rejects quarantined files anywhere in the submitted bundle (91109).
    if has_quarantine(app):
        run("xattr", "-drs", QUARANTINE, app)
    verify_no_quarantine(app)

    app_entitlements = app.parent / "app-entitlements.plist"
    app_entitlements.write_bytes(plistlib.dumps({
        SANDBOX: True,
        "com.apple.application-identifier": application_id,
        "com.apple.developer.team-identifier": application_id.split(".", 1)[0],
        "com.apple.security.files.user-selected.read-write": True,
        "com.apple.security.network.client": True,
        "com.apple.security.network.server": True,
    }))
    helper_entitlements = app.parent / "helper-entitlements.plist"
    helper_entitlements.write_bytes(plistlib.dumps({SANDBOX: True, "com.apple.security.inherit": True}))

    sign = ["codesign", "--force", "--timestamp", "--sign", identity]
    frameworks = contents / "Frameworks"
    ffmpeg = (frameworks / "bin" / "ffmpeg").resolve(strict=True)
    for path in frameworks.rglob("*"):
        if path.is_symlink() or not path.is_file():
            continue
        with path.open("rb") as stream:
            if stream.read(4) not in MACH_O:
                continue
        if path.resolve() == ffmpeg:
            run(*sign, "--identifier", f"{BUNDLE_ID}.ffmpeg", "--entitlements", helper_entitlements, path)
        else:
            run(*sign, path)

    for framework in sorted(frameworks.rglob("*.framework"), key=lambda path: -len(path.parts)):
        if not framework.is_symlink():
            run(*sign, framework)
    run(*sign, "--entitlements", app_entitlements, app)
    run("codesign", "--verify", "--deep", "--strict", app)


def package(source: Path, stage: Path, dist: Path, version: str, args: argparse.Namespace,
            application_id: str) -> Path:
    app = stage / f"{APP_NAME}.app"
    run("ditto", "--noqtn", source, app)
    info = app / "Contents" / "Info.plist"
    metadata = plistlib.loads(info.read_bytes())
    metadata.update(
        CFBundleIdentifier=BUNDLE_ID,
        CFBundleName=APP_NAME,
        CFBundleDisplayName=APP_NAME,
        CFBundleShortVersionString=version,
        CFBundleVersion=str(args.build_number),
        LSApplicationCategoryType="public.app-category.sports",
        NSHumanReadableCopyright="2026 John Sung Lee",
        ITSAppUsesNonExemptEncryption=False,
    )
    info.write_bytes(plistlib.dumps(metadata))
    sign_bundle(app, args.profile, application_id, args.app_identity)

    pkg = stage / f"{APP_NAME}-{version}-build{args.build_number}-MacAppStore-arm64.pkg"
    run("productbuild", "--component", app, "/Applications", "--sign", args.installer_identity, pkg)
    run("pkgutil", "--check-signature", pkg)
    expanded = stage / "expanded"
    run("pkgutil", "--expand-full", pkg, expanded)
    (installed,) = expanded.rglob(f"{APP_NAME}.app")
    verify_no_quarantine(installed)
    if (installed / "Contents" / "embedded.provisionprofile").read_bytes() != args.profile.read_bytes():
        raise RuntimeError("The packaged provisioning profile differs from the input")
    run("codesign", "--verify", "--deep", "--strict", installed)
    out = dist / pkg.name
    shutil.copyfile(pkg, out)
    with out.open("rb") as stream:
        checksum = hashlib.file_digest(stream, "sha256").hexdigest()
    out.with_suffix(".pkg.sha256").write_text(f"{checksum}  {out.name}\n")
    return out
