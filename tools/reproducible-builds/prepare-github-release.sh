#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RELEASE_DIR="${1:?usage: prepare-github-release.sh RELEASE_DIR}"

if [ ! -d "$RELEASE_DIR" ]; then
  echo "error: release directory not found" >&2
  exit 1
fi
RELEASE_DIR="$(cd "$RELEASE_DIR" && pwd)"

if command -v sha256sum >/dev/null 2>&1; then
  SHA256=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  SHA256=(shasum -a 256)
else
  echo "error: sha256sum or shasum is required" >&2
  exit 1
fi

required=(
  BUILDINFO.json
  NATIVE-SYMBOLS-BUILDINFO.json
  NATIVE-SYMBOLS-SHA256SUMS
  SHA256SUMS
  SHA256SUMS.unsigned
  bitchat-android-arm64-unsigned.apk
  bitchat-android-arm64.apk
  bitchat-android-armv7-unsigned.apk
  bitchat-android-play-upload.aab
  bitchat-android-release-unsigned.aab
  bitchat-android-universal-unsigned.apk
  bitchat-android-universal.apk
  bitchat-android-x86-unsigned.apk
  bitchat-android-x86_64-unsigned.apk
  bitchat-android-x86_64.apk
  wesh-android-native-debug-symbols.zip
)
for artifact in "${required[@]}"; do
  if [ ! -f "$RELEASE_DIR/$artifact" ]; then
    echo "error: required release artifact missing: $artifact" >&2
    exit 1
  fi
done

for artifact_path in "$RELEASE_DIR"/*; do
  if [ ! -f "$artifact_path" ]; then
    echo "error: unexpected non-file in release directory: $(basename "$artifact_path")" >&2
    exit 1
  fi
  artifact="$(basename "$artifact_path")"
  case "$artifact" in
    BUILDINFO.json|NATIVE-SYMBOLS-BUILDINFO.json|NATIVE-SYMBOLS-SHA256SUMS|SHA256SUMS|SHA256SUMS.unsigned|\
    bitchat-android-arm64-unsigned.apk|bitchat-android-arm64.apk|\
    bitchat-android-armv7-unsigned.apk|\
    bitchat-android-play-upload.aab|bitchat-android-release-unsigned.aab|\
    bitchat-android-universal-unsigned.apk|bitchat-android-universal.apk|\
    bitchat-android-x86-unsigned.apk|\
    bitchat-android-x86_64-unsigned.apk|bitchat-android-x86_64.apk|\
    wesh-android-native-debug-symbols.zip)
      ;;
    *)
      echo "error: unexpected release artifact: $artifact" >&2
      exit 1
      ;;
  esac
done

for destination in BITCHAT_BUILDINFO.json BITCHAT_SHA256SUMS BITCHAT_SHA256SUMS.unsigned; do
  if [ -e "$RELEASE_DIR/$destination" ]; then
    echo "error: public release manifest already exists: $destination" >&2
    exit 1
  fi
done

(
  cd "$RELEASE_DIR"
  "${SHA256[@]}" -c NATIVE-SYMBOLS-SHA256SUMS
  "${SHA256[@]}" -c SHA256SUMS
)

python3 "$PROJECT_ROOT/tools/arti-build/native_symbols.py" verify-release \
  --archive "$RELEASE_DIR/wesh-android-native-debug-symbols.zip" \
  --buildinfo "$RELEASE_DIR/NATIVE-SYMBOLS-BUILDINFO.json" \
  --aab "$RELEASE_DIR/bitchat-android-release-unsigned.aab"

mv "$RELEASE_DIR/BUILDINFO.json" "$RELEASE_DIR/BITCHAT_BUILDINFO.json"
mv "$RELEASE_DIR/SHA256SUMS.unsigned" "$RELEASE_DIR/BITCHAT_SHA256SUMS.unsigned"
sed \
  -e 's/  BUILDINFO.json$/  BITCHAT_BUILDINFO.json/' \
  -e 's/  SHA256SUMS.unsigned$/  BITCHAT_SHA256SUMS.unsigned/' \
  "$RELEASE_DIR/SHA256SUMS" > "$RELEASE_DIR/BITCHAT_SHA256SUMS"
rm "$RELEASE_DIR/SHA256SUMS"

(
  cd "$RELEASE_DIR"
  "${SHA256[@]}" -c BITCHAT_SHA256SUMS
)

echo "Release assets are checksummed and ready for manual GitHub publication."
