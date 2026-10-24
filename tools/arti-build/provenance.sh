#!/usr/bin/env bash
# Shared native input and privacy checks; callers use set -euo pipefail.
NATIVE_SOURCE_INPUTS=(Cargo.lock Cargo.toml Dockerfile TOOLCHAIN.env rust-toolchain.toml build-arti.sh native_symbols.py provenance.sh rebuild-in-container.sh src/lib.rs)

reject_unsupported_patches() {
  local directory="$1"
  if [ -d "$directory/patches" ] && [ -n "$(ls -A "$directory/patches")" ]; then
    echo "error: native patches are not supported by the source manifest" >&2
    return 1
  fi
}

write_source_checksums() {
  local directory="$1" output="$2" input
  : > "$output" || return 1
  for input in "${NATIVE_SOURCE_INPUTS[@]}"; do
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum "$directory/$input" | sed "s#  $directory/#  tools/arti-build/#" >> "$output" || return 1
    else
      shasum -a 256 "$directory/$input" | sed "s#  $directory/#  tools/arti-build/#" >> "$output" || return 1
    fi
  done
}

capture_source_inputs() {
  local directory="$1" snapshot="$2" input
  reject_unsupported_patches "$directory" || return 1
  mkdir -p "$snapshot/src" || return 1
  for input in "${NATIVE_SOURCE_INPUTS[@]}"; do
    cp "$directory/$input" "$snapshot/$input" || return 1
    chmod a-w "$snapshot/$input" || return 1
  done
  write_source_checksums "$snapshot" "$snapshot/SOURCE_SHA256SUMS" || return 1
  chmod a-w "$snapshot/SOURCE_SHA256SUMS"
}

assert_source_inputs_unchanged() {
  local directory="$1" snapshot="$2" current
  reject_unsupported_patches "$directory" || return 1
  current="$(mktemp)" || return 1
  if ! write_source_checksums "$directory" "$current"; then
    rm -f "$current"
    return 1
  fi
  if ! cmp -s "$snapshot/SOURCE_SHA256SUMS" "$current"; then
    rm -f "$current"
    echo "error: native sources changed during build; rebuild all four ABIs" >&2
    return 1
  fi
  rm -f "$current"
}

assert_no_host_paths() {
  local library="$1" scan
  scan="$(mktemp)" || return 1
  if ! strings "$library" > "$scan"; then
    rm -f "$scan"
    echo "error: native library path scan failed" >&2
    return 1
  fi
  # Consume the completed scan, avoiding grep -q / SIGPIPE acceptance under pipefail.
  local scan_status=0
  grep -E '/Users/|/home/|/workspace/|/\.arti-source/|/\.reproducible-build/|[A-Za-z]:\\Users\\' "$scan" > /dev/null || scan_status=$?
  rm -f "$scan"
  case "$scan_status" in
    0) echo "error: host-specific path detected in a native library" >&2; return 1 ;;
    1) return 0 ;;
    *) echo "error: native library path scan failed" >&2; return 1 ;;
  esac
}

assert_64bit_load_alignment() {
  local library="$1" readelf="$2" headers alignments alignment count=0
  if [ ! -x "$readelf" ] || ! headers="$("$readelf" -lW "$library")"; then
    echo "error: pinned readelf failed" >&2
    return 1
  fi
  alignments="$(printf '%s\n' "$headers" | awk '$1 == "LOAD" {print $NF}')" || return 1
  while IFS= read -r alignment; do
    if ! [[ "$alignment" =~ ^0x[0-9a-fA-F]+$ ]] ||
       (( alignment < 16384 || (alignment & (alignment - 1)) != 0 )); then
      echo "error: 64-bit LOAD segment alignment is invalid or below 16 KiB" >&2
      return 1
    fi
    count=$((count + 1))
  done <<< "$alignments"
  if [ "$count" -eq 0 ]; then
    echo "error: no 64-bit LOAD segments found" >&2
    return 1
  fi
}
