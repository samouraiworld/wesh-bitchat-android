#!/usr/bin/env python3
"""Check that release APKs contain no capture activity/application or fixture assets."""
import argparse
from pathlib import Path
import zipfile


def verify(path: Path) -> None:
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if name.endswith("store_scenes.json"):
                raise ValueError("store fixture asset leaked into Release")
            if name.endswith(".dex"):
                data = apk.read(name)
                if any(marker in data for marker in (b"StoreScreenshotActivity", b"StoreCaptureApplication", b"WESH-SYNTHETIC-STORE")):
                    raise ValueError("store-capture class/data leaked into Release")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    apks = sorted(args.directory.glob("*.apk"))
    if not apks:
        parser.error("no built Release APK found")
    for apk in apks:
        verify(apk)
    print("Release APKs exclude capture classes and fixture assets")
