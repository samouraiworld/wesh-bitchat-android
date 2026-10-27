#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'ui-reader-build-fail\n' >&2; exit 1; }
[[ "$#" == 1 ]] || fail
output="$1"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk_root" && -d "$sdk_root" ]] || fail
android_jar="$sdk_root/platforms/android-35/android.jar"
d8="$sdk_root/build-tools/37.0.0/d8"
source_file="tools/fgs-evidence/ui-reader/UiHierarchyReader.java"
[[ -f "$android_jar" && ! -L "$android_jar" && -s "$android_jar" ]] || fail
[[ -x "$d8" && ! -L "$d8" && -f "$d8" ]] || fail
[[ -f "$source_file" && ! -L "$source_file" ]] || fail
[[ "$(basename "$output")" == wesh-ui-reader.jar ]] || fail
parent="$(dirname "$output")"
[[ -d "$parent" && ! -L "$parent" ]] || fail

work="$(mktemp -d "$parent/ui-reader-build.XXXXXXXX")" || fail
cleanup() { rm -rf -- "$work"; }
trap cleanup EXIT
mkdir -p "$work/classes" || fail
javac -source 8 -target 8 -bootclasspath "$android_jar" \
  -d "$work/classes" "$source_file" >"$work/javac.log" 2>&1 || fail
jar --create --file "$work/classes.jar" -C "$work/classes" . \
  >"$work/jar.log" 2>&1 || fail
"$d8" --min-api 35 --output "$work/reader.jar" "$work/classes.jar" \
  >"$work/d8.log" 2>&1 || fail
[[ -f "$work/reader.jar" && ! -L "$work/reader.jar" && -s "$work/reader.jar" ]] || fail
mv "$work/reader.jar" "$output" || fail
printf 'ui-reader-build-pass\n'
