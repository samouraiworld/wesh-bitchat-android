#!/usr/bin/env python3
"""Keep a temporary frame only after three identical pixel samples within a deadline."""
from __future__ import annotations

import argparse
import hashlib
import io
from pathlib import Path
import re
import subprocess
import time

from PIL import Image


def pixels(raw: bytes) -> str:
    with Image.open(io.BytesIO(raw)) as image:
        if image.format != "PNG" or image.size != (1080, 1920):
            raise ValueError("unexpected synthetic frame geometry")
        return hashlib.sha256(image.convert("RGBA").tobytes()).hexdigest()


def stable_frame(capture, *, timeout=15.0, interval=0.2, clock=time.monotonic, sleep=time.sleep) -> bytes:
    deadline = clock() + timeout
    previous = None
    consecutive = 0
    while True:
        remaining = deadline - clock()
        if remaining <= 0:
            raise TimeoutError("pixels never stabilized")
        raw = capture(remaining)
        current = pixels(raw)
        if clock() > deadline:
            raise TimeoutError("pixel sampling exceeded deadline")
        consecutive = consecutive + 1 if current == previous else 1
        previous = current
        if consecutive >= 3:
            return raw
        sleep(min(interval, max(0, deadline - clock())))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.target) or args.output.exists():
        parser.error("require an explicit synthetic emulator and a new temporary frame")
    try:
        is_emulator = subprocess.check_output(
            ["adb", "-s", args.target, "shell", "getprop", "ro.kernel.qemu"], timeout=5
        ).strip() == b"1"
        if not is_emulator:
            raise ValueError("physical capture is forbidden")

        def capture(remaining):
            return subprocess.check_output(
                ["adb", "-s", args.target, "exec-out", "screencap", "-p"], timeout=remaining
            )

        args.output.write_bytes(stable_frame(capture))
    except (OSError, ValueError, TimeoutError, subprocess.SubprocessError):
        parser.exit(1, "Synthetic scene did not reach three stable pixel samples within its deadline.\n")
    print("Synthetic scene reached three identical pixel samples")


if __name__ == "__main__":
    main()
