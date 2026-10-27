#!/usr/bin/env python3
"""Bind synthetic captures to unchanged production sources; never auto-approve pixels."""
from __future__ import annotations

import argparse
import hashlib
import json
import struct
import subprocess
from pathlib import Path

from PIL import Image

PRODUCTION_COMMIT = "753769a07fa9e62c87afead88ea10ef42b823788"
SCENES = ("home", "public", "private", "exchange", "settings", "connection", "about")
EXPECTED_ENVIRONMENT = {"emulator": True, "api": 35, "width": 1080, "height": 1920, "densityDpi": 420}
ROOT_PATHS = (
    "app/src/main/java/com/bitchat/android/MainActivity.kt",
    "app/src/main/java/com/bitchat/android/ui/WeshScreen.kt",
    "app/src/main/java/com/bitchat/android/ui/pave/WeshPaveApp.kt",
    "app/src/main/java/com/bitchat/android/ui/pave/WeshPaveTheme.kt",
)
CAPTURE_PATHS = (
    ".github/workflows/store-capture.yml",
    "app/build.gradle.kts",
    "app/src/storeScreenshot/java/com/bitchat/android/store/StoreScreenshotActivity.kt",
    "app/src/storeScreenshot/java/com/bitchat/android/store/StoreCaptureApplication.kt",
    "app/src/storeScreenshot/assets/store_scenes.json",
    "app/src/storeScreenshot/AndroidManifest.xml",
    "tools/store-assets/capture_android_store_screenshots.sh",
    "tools/store-assets/sanitize_store_screenshot.py",
    "tools/store-assets/requirements.txt",
    "tools/store-assets/wait_store_capture_stability.py",
    "tools/store-assets/verify_capture_release_boundary.py",
    "tools/store-assets/verify_store_manifest.py",
    "tools/store-assets/store_capture_manifest.py",
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def source_proof(root: Path) -> dict:
    # Includes staged and unstaged changes, unlike comparing HEAD tree IDs alone.
    subprocess.run(["git", "-C", str(root), "diff", "--exit-code", "--quiet",
                    PRODUCTION_COMMIT, "--", "app/src/main", "app/build.gradle.kts"], check=True)
    if git(root, "ls-files", "--others", "--exclude-standard", "--", "app/src/main"):
        raise ValueError("untracked production source prevents candidate binding")
    subprocess.run(["git", "-C", str(root), "diff", "--exit-code", "--quiet", "HEAD", "--", *CAPTURE_PATHS], check=True)
    if git(root, "ls-files", "--others", "--exclude-standard", "--", *CAPTURE_PATHS):
        raise ValueError("capture inputs must be committed before recording their source")
    files = {}
    for relative in ROOT_PATHS:
        expected = subprocess.check_output(["git", "-C", str(root), "show", f"{PRODUCTION_COMMIT}:{relative}"])
        if (root / relative).read_bytes() != expected:
            raise ValueError(f"production source changed: {relative}")
        files[relative] = hashlib.sha256(expected).hexdigest()
    return {
        "productionCandidateCommit": PRODUCTION_COMMIT,
        "productionAppMainTree": git(root, "rev-parse", f"{PRODUCTION_COMMIT}:app/src/main"),
        "productionRootSources": files,
        "captureSources": {name: digest(root / name) for name in CAPTURE_PATHS},
        "capturedFromCommit": git(root, "rev-parse", "HEAD"),
    }


def image_record(folder: Path, relative: str) -> dict:
    path = folder / relative
    raw = path.read_bytes()
    position = 8
    while position < len(raw):
        length = struct.unpack(">I", raw[position:position + 4])[0]
        if raw[position + 4:position + 8] not in (b"IHDR", b"IDAT", b"IEND"):
            raise ValueError(f"metadata chunk in screenshot: {relative}")
        position += length + 12
    if position != len(raw):
        raise ValueError(f"invalid PNG structure: {relative}")
    with Image.open(path) as image:
        if image.format != "PNG" or image.size != (1080, 1920) or image.mode != "RGBA":
            raise ValueError(f"unexpected screenshot geometry/mode: {relative}")
        if image.info:
            raise ValueError(f"metadata in screenshot: {relative}")
        image.verify()
    with Image.open(path) as image:
        if image.getchannel("A").getextrema() != (255, 255):
            raise ValueError(f"transparency in screenshot: {relative}")
    return {"path": relative, "sha256": digest(path), "width": 1080, "height": 1920}


def image_records(folder: Path) -> list[dict]:
    expected = [f"{locale}/phoneScreenshots/{index:02}-{scene}.png"
                for locale in ("fr-FR", "en-US") for index, scene in enumerate(SCENES, 1)]
    if sorted(str(path.relative_to(folder)) for path in folder.rglob("*.png")) != sorted(expected):
        raise ValueError("exactly fourteen canonical screenshots are required")
    for path in folder.rglob("*"):
        if path.is_symlink() or (path.is_file() and str(path.relative_to(folder)) not in {*expected, "manifest.json"}):
            raise ValueError("unexpected artifact file or symlink")
    return [image_record(folder, name) for name in expected]


def validate_environment(context: dict) -> dict:
    if context != EXPECTED_ENVIRONMENT:
        raise ValueError("capture context differs from the controlled synthetic environment")
    return context


def create(root: Path, folder: Path, context: dict) -> dict:
    return {
        "schema": 1,
        "synthetic": True,
        "bindingStatus": "UNBOUND_REVIEW_REQUIRED",
        "captureCommit": None,
        "rootInfrastructure": "Mirrored standard Theme/Scaffold, not a shared production root; real product screen composables",
        "productionPath": "MainActivity -> OnboardingFlowScreen COMPLETE -> WeshScreen -> WeshPaveApp",
        "insets": "COMPLETE does not forward Scaffold padding; unchanged product screens consume their own systemBarsPadding",
        "systemBars": "visible; standard edge-to-edge; no per-scene contrast override",
        "review": "Lead visual review and explicit binding required; not evidence of transport or physical qualification",
        "environment": validate_environment(context),
        **source_proof(root),
        "screenshots": image_records(folder),
    }


def verify(root: Path, folder: Path, require_reviewed: bool = False) -> dict:
    manifest = json.loads((folder / "manifest.json").read_text())
    proof = source_proof(root)
    for key, value in proof.items():
        if manifest.get(key) != value:
            raise ValueError(f"source/capture binding differs: {key}")
    if manifest.get("screenshots") != image_records(folder) or manifest.get("synthetic") is not True:
        raise ValueError("screenshot evidence differs")
    validate_environment(manifest.get("environment"))
    unbound = manifest.get("bindingStatus") == "UNBOUND_REVIEW_REQUIRED" and manifest.get("captureCommit") is None
    reviewed = manifest.get("bindingStatus") == "REVIEWED" and manifest.get("captureCommit") == proof["capturedFromCommit"]
    if not (reviewed or (unbound and not require_reviewed)):
        raise ValueError("visual review/binding has not been confirmed")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("create", "verify"))
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--require-reviewed", action="store_true")
    parser.add_argument("--context", type=Path)
    args = parser.parse_args()
    if args.operation == "create":
        if args.require_reviewed:
            parser.error("capture generation never approves its own pixels")
        if args.context is None:
            parser.error("capture creation requires the controlled synthetic context readback")
        (args.output / "manifest.json").write_text(json.dumps(create(args.root, args.output, json.loads(args.context.read_text())), indent=2) + "\n")
    else:
        verify(args.root, args.output, args.require_reviewed)
    print("Synthetic capture sources and fourteen sanitized images verified; review status remains explicit")


if __name__ == "__main__":
    main()
