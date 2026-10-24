#!/usr/bin/env bash

set -euo pipefail

FIRST_DIR="${1:?usage: compare-symbols.sh FIRST_DIR SECOND_DIR}"
SECOND_DIR="${2:?usage: compare-symbols.sh FIRST_DIR SECOND_DIR}"

artifacts=(
  NATIVE-SYMBOLS-BUILDINFO.json
  NATIVE-SYMBOLS-SHA256SUMS
  wesh-android-native-debug-symbols.zip
)

for artifact in "${artifacts[@]}"; do
  if ! cmp -s "$FIRST_DIR/$artifact" "$SECOND_DIR/$artifact"; then
    echo "error: native-symbol reproducibility mismatch: $artifact" >&2
    exit 1
  fi
  echo "MATCH $artifact"
done

echo "All native-symbol release artifact bytes match."
