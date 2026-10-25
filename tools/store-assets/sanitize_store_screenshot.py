#!/usr/bin/env python3
"""Validate and rewrite an emulator screenshot without ancillary PNG metadata."""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image


if len(sys.argv) != 3:
    raise SystemExit("usage: sanitize_store_screenshot.py INPUT OUTPUT")

source, destination = map(Path, sys.argv[1:])
with Image.open(source) as image:
    if image.size != (1080, 1920):
        raise SystemExit(f"expected a 1080x1920 emulator capture, got {image.size}")
    clean = image.convert("RGBA")
    if clean.getchannel("A").getextrema() != (255, 255):
        raise SystemExit("a store capture must be opaque")
    clean.info.clear()
    destination.parent.mkdir(parents=True, exist_ok=True)
    clean.save(destination, "PNG", optimize=False, compress_level=9)
