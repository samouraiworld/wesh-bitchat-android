#!/usr/bin/env bash
set -euo pipefail

root_dir="$(git rev-parse --show-toplevel)"
cd "$root_dir"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is unavailable; no screenshots were created." >&2
  exit 2
fi

serial="${ANDROID_STORE_CAPTURE_SERIAL:-}"
if [[ ! "$serial" =~ ^emulator-[0-9]+$ ]]; then
  echo "An explicit dedicated emulator target is required; no device inventory is read." >&2
  exit 2
fi
output_dir="${ANDROID_STORE_CAPTURE_OUTPUT:?Set a fresh output directory for the reviewed candidate}"
if [[ -e "$output_dir" ]]; then
  echo "Refusing to replace existing capture evidence." >&2
  exit 2
fi

if [[ "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "Refusing capture: physical devices and their data are out of scope." >&2
  exit 3
fi
api="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
density="$(adb -s "$serial" shell wm density | tr -d '\r' | sed -nE 's/^(Physical|Override) density: ([0-9]+)$/\2/p' | tail -1)"
if [[ "$api" != 35 || "$density" != 420 ]]; then
  echo "The synthetic capture context must be API35 and density420." >&2
  exit 3
fi

size_line="$(adb -s "$serial" shell wm size | tr -d '\r')"
if [[ "$size_line" != *"1080x1920"* ]]; then
  echo "Use a dedicated 1080x1920 portrait emulator." >&2
  exit 3
fi

# Keep real system bars and normal edge-to-edge insets. Only system demo values
# and animation scale are deterministic on this isolated synthetic emulator.
adb -s "$serial" shell settings put global animator_duration_scale 0 >/dev/null
adb -s "$serial" shell settings put global transition_animation_scale 0 >/dev/null
adb -s "$serial" shell settings put global window_animation_scale 0 >/dev/null
adb -s "$serial" shell cmd uimode night yes >/dev/null
adb -s "$serial" shell settings put global sysui_demo_allowed 1 >/dev/null
adb -s "$serial" shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb -s "$serial" shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
adb -s "$serial" shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb -s "$serial" shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile hide >/dev/null
adb -s "$serial" shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

apk="${ANDROID_STORE_CAPTURE_APK:-app/build/outputs/apk/storeScreenshot/app-universal-storeScreenshot.apk}"
if [[ ! -f "$apk" ]]; then
  echo "Build the isolated store-capture APK before running this script." >&2
  exit 4
fi

package_name="org.samouraiworld.wesh.android.storecapture"
activity_name="com.bitchat.android.store.StoreScreenshotActivity"
adb -s "$serial" install -r "$apk" >/dev/null

temporary_dir="$(mktemp -d)"
trap 'adb -s "$serial" shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null 2>&1 || true; rm -rf "$temporary_dir"' EXIT
printf '{"emulator":true,"api":%s,"width":1080,"height":1920,"densityDpi":%s}\n' "$api" "$density" > "$temporary_dir/context.json"
scenes=(home public private exchange settings connection about)

for language in fr en; do
  if [[ "$language" == "fr" ]]; then
    locale_dir="fr-FR"
  else
    locale_dir="en-US"
  fi
  index=1
  for scene in "${scenes[@]}"; do
    adb -s "$serial" shell am force-stop "$package_name"
    adb -s "$serial" logcat -c
    adb -s "$serial" shell am start -W \
      -n "$package_name/$activity_name" \
      --es scene "$scene" \
      --es language "$language" >/dev/null
    ready=false
    for attempt in $(seq 1 100); do
      if adb -s "$serial" logcat -d -v raw -s WeshStoreCapture:I '*:S' | tr -d '\r' | grep -Fxq "READY $language $scene"; then
        ready=true
        break
      fi
      sleep 0.2
    done
    if [[ "$ready" != true ]]; then
      echo "Capture scene did not report completed composition frames." >&2
      exit 5
    fi
    raw="$temporary_dir/$language-$scene.png"
    destination="$output_dir/$locale_dir/phoneScreenshots/$(printf '%02d' "$index")-$scene.png"
    python3 tools/store-assets/wait_store_capture_stability.py --target "$serial" --output "$raw"
    python3 tools/store-assets/sanitize_store_screenshot.py "$raw" "$destination"
    index=$((index + 1))
  done
done

python3 tools/store-assets/store_capture_manifest.py create --output "$output_dir" --context "$temporary_dir/context.json"
python3 tools/store-assets/store_capture_manifest.py verify --output "$output_dir"
echo "Captured fourteen sanitized synthetic scenes; visual review and binding remain required."
