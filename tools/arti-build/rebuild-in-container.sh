#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
IMAGE_NAME="bitchat-android-arti-builder:1.9.0"
MODE="${1:-}"
OUTPUT_DIR="${ARTI_SYMBOL_OUTPUT_DIR:-$PROJECT_ROOT/.reproducible-build/arti-native-symbols-release}"

if ! command -v docker >/dev/null 2>&1; then
  echo "error: Docker is required to rebuild the native Arti libraries" >&2
  exit 1
fi

mkdir -p "$PROJECT_ROOT/.reproducible-build/cargo-home"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/TOOLCHAIN.env"
if ! [[ "$NATIVE_SOURCE_DATE_EPOCH" =~ ^[0-9]+$ ]]; then
  echo "error: NATIVE_SOURCE_DATE_EPOCH must be an integer" >&2
  exit 1
fi

# The captured input snapshot is read-only. Its published checksum copy can
# retain that mode; allow a later full rebuild to replace this output manifest.
if [ -f "$SCRIPT_DIR/SOURCE_SHA256SUMS" ]; then
  chmod u+w "$SCRIPT_DIR/SOURCE_SHA256SUMS"
fi

if [ "$MODE" != "--update-lockfile" ]; then
  mkdir -p "$OUTPUT_DIR"
  OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
  if [ -n "$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    echo "error: native symbol output directory must be empty: $OUTPUT_DIR" >&2
    exit 1
  fi
fi

docker build \
  --platform linux/amd64 \
  --file "$SCRIPT_DIR/Dockerfile" \
  --tag "$IMAGE_NAME" \
  "$PROJECT_ROOT"

docker_args=(
  --rm
  --platform linux/amd64
  --user "$(id -u):$(id -g)"
  --env CARGO_HOME=/workspace/.reproducible-build/cargo-home
  --env HOME=/workspace/.reproducible-build
  --env SOURCE_DATE_EPOCH="$NATIVE_SOURCE_DATE_EPOCH"
  --env CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-1}"
  --volume "$PROJECT_ROOT:/workspace"
)
if [ "$MODE" != "--update-lockfile" ]; then
  docker_args+=(
    --env ARTI_SYMBOL_OUTPUT_DIR=/native-output
    --volume "$OUTPUT_DIR:/native-output"
  )
fi

docker run "${docker_args[@]}" "$IMAGE_NAME" ${MODE:+"$MODE"}

if [ "$MODE" = "--update-lockfile" ]; then
  echo "Cargo.lock updated in the pinned native container; review it before rebuilding."
  exit 0
fi
"$SCRIPT_DIR/verify-checksums.sh"
test -f "$OUTPUT_DIR/wesh-android-native-debug-symbols.zip"
test -f "$OUTPUT_DIR/NATIVE-SYMBOLS-BUILDINFO.json"
test -f "$OUTPUT_DIR/NATIVE-SYMBOLS-SHA256SUMS"
