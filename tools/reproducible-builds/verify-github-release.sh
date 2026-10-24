#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TAG="${1:?usage: verify-github-release.sh TAG [--no-rebuild]}"
MODE="${2:-}"
# Prefer the Wesh-specific override while retaining the historical environment
# variable as a compatibility fallback for existing verifier automation.
REPOSITORY="${WESH_GITHUB_REPOSITORY:-${BITCHAT_GITHUB_REPOSITORY:-samouraiworld/wesh-bitchat-android}}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

if ! [[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  echo "error: release tag must look like vX.Y.Z" >&2
  exit 1
fi
if [ -n "$MODE" ] && [ "$MODE" != "--no-rebuild" ]; then
  echo "error: unknown option: $MODE" >&2
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "error: gh is required" >&2
  exit 1
fi
if command -v sha256sum >/dev/null 2>&1; then
  SHA256=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  SHA256=(shasum -a 256)
else
  echo "error: sha256sum or shasum is required" >&2
  exit 1
fi

DOWNLOAD_DIR="$TEMP_DIR/github"
mkdir -p "$DOWNLOAD_DIR"
gh release download "$TAG" \
  --repo "$REPOSITORY" \
  --dir "$DOWNLOAD_DIR" \
  --pattern 'BITCHAT_BUILDINFO.json' \
  --pattern 'BITCHAT_SHA256SUMS' \
  --pattern 'BITCHAT_SHA256SUMS.unsigned' \
  --pattern 'NATIVE-SYMBOLS-BUILDINFO.json' \
  --pattern 'NATIVE-SYMBOLS-SHA256SUMS' \
  --pattern 'bitchat-android-*.apk' \
  --pattern 'bitchat-android-*.aab' \
  --pattern 'wesh-android-native-debug-symbols.zip'

attested_artifacts=(
  BITCHAT_BUILDINFO.json
  BITCHAT_SHA256SUMS.unsigned
  NATIVE-SYMBOLS-BUILDINFO.json
  NATIVE-SYMBOLS-SHA256SUMS
  bitchat-android-arm64-unsigned.apk
  bitchat-android-armv7-unsigned.apk
  bitchat-android-release-unsigned.aab
  bitchat-android-universal-unsigned.apk
  bitchat-android-x86-unsigned.apk
  bitchat-android-x86_64-unsigned.apk
  wesh-android-native-debug-symbols.zip
)
for artifact in "${attested_artifacts[@]}"; do
  if [ ! -f "$DOWNLOAD_DIR/$artifact" ]; then
    echo "error: attested canonical artifact missing: $artifact" >&2
    exit 1
  fi
  gh attestation verify "$DOWNLOAD_DIR/$artifact" --repo "$REPOSITORY" >/dev/null
done
echo "GitHub provenance attestations for the canonical unsigned build verified."

python3 "$PROJECT_ROOT/tools/arti-build/native_symbols.py" verify-release \
  --archive "$DOWNLOAD_DIR/wesh-android-native-debug-symbols.zip" \
  --buildinfo "$DOWNLOAD_DIR/NATIVE-SYMBOLS-BUILDINFO.json" \
  --aab "$DOWNLOAD_DIR/bitchat-android-release-unsigned.aab"

(
  cd "$DOWNLOAD_DIR"
  "${SHA256[@]}" -c NATIVE-SYMBOLS-SHA256SUMS
  "${SHA256[@]}" -c BITCHAT_SHA256SUMS
)
echo "GitHub release checksums verified."

mv "$DOWNLOAD_DIR/BITCHAT_BUILDINFO.json" "$DOWNLOAD_DIR/BUILDINFO.json"
mv "$DOWNLOAD_DIR/BITCHAT_SHA256SUMS" "$DOWNLOAD_DIR/SHA256SUMS"
mv "$DOWNLOAD_DIR/BITCHAT_SHA256SUMS.unsigned" "$DOWNLOAD_DIR/SHA256SUMS.unsigned"

if [ "$MODE" = "--no-rebuild" ]; then
  exit 0
fi

release_commit="$(sed -n 's/.*"sourceCommit": *"\([^"]*\)".*/\1/p' "$DOWNLOAD_DIR/BUILDINFO.json")"
local_commit="$(git -C "$PROJECT_ROOT" rev-parse HEAD)"
if [ "$release_commit" != "$local_commit" ]; then
  echo "error: check out release commit $release_commit before rebuilding" >&2
  exit 1
fi

LOCAL_DIR="$PROJECT_ROOT/.reproducible-build/verify-$TAG"
if [ -e "$LOCAL_DIR" ]; then
  echo "error: local verification output already exists: $LOCAL_DIR" >&2
  exit 1
fi

"$SCRIPT_DIR/build-in-container.sh" "$LOCAL_DIR"
"$SCRIPT_DIR/compare-release.sh" "$LOCAL_DIR" "$DOWNLOAD_DIR"
LOCAL_SYMBOL_DIR="$PROJECT_ROOT/.reproducible-build/verify-$TAG-native-symbols"
if [ -e "$LOCAL_SYMBOL_DIR" ]; then
  echo "error: local native-symbol verification output already exists: $LOCAL_SYMBOL_DIR" >&2
  exit 1
fi
ARTI_SYMBOL_OUTPUT_DIR="$LOCAL_SYMBOL_DIR" "$PROJECT_ROOT/tools/arti-build/rebuild-in-container.sh"
"$PROJECT_ROOT/tools/arti-build/compare-symbols.sh" "$LOCAL_SYMBOL_DIR" "$DOWNLOAD_DIR"
echo "GitHub release $TAG was reproduced successfully from source."
