#!/usr/bin/env bash
set -euo pipefail

# This script deliberately has no physical-device fallback and never calls unscoped adb shell.
root=""
failure_gate=runner_prerequisite_unclassified

write_failure_receipt() {
  if [[ -n "$root" ]]; then
    python3 tools/fgs-evidence/diagnostic_failure_receipt.py \
      "$root" "$1" >/dev/null 2>&1 || true
  fi
}

fail() {
  trap - ERR
  write_failure_receipt "$1"
  printf 'FGS prerequisite failed: %s\n' "$1" >&2
  exit 1
}

unexpected_failure() {
  local status="$1"
  trap - ERR
  write_failure_receipt "$failure_gate"
  printf 'FGS prerequisite failed: %s\n' "$failure_gate" >&2
  exit "$status"
}

trap 'unexpected_failure "$?"' ERR
[[ "${GITHUB_ACTIONS:-}" == true && "$(uname -s)" == Linux ]] || fail runner_context
[[ $# -eq 1 ]] || fail runner_prerequisite_unclassified
root="$1"
[[ "$root" == "$RUNNER_TEMP/fgs-evidence" && -f "$root/wesh109-test-signed.apk" ]] || fail verified_test_apk
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk_root" ]] || fail sdk_root_missing
export ANDROID_SDK_ROOT="$sdk_root"
export ANDROID_AVD_HOME="$root/avd"
export ANDROID_USER_HOME="$root/android-user"
failure_gate=runner_workspace_setup
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME" >/dev/null 2>&1 \
  || fail runner_workspace_setup
export PATH="$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

cleanup() {
  for serial in emulator-5554 emulator-5556; do
    timeout 8 adb -s "$serial" emu kill >/dev/null 2>&1 || true
  done
  # Only processes launched by this script, never another runner/device or a shared adb server.
  for pid in "${avd_a_pid:-}" "${avd_b_pid:-}"; do
    if [[ "$pid" =~ ^[0-9]+$ ]]; then
      kill "$pid" 2>/dev/null || true
      for _ in {1..20}; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.25
      done
      kill -9 "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT

command -v sdkmanager >/dev/null || fail sdkmanager_missing
[[ -e /dev/kvm ]] || fail kvm_missing
sudo chmod a+rw /dev/kvm >/dev/null 2>&1 || fail kvm_permission_setup
[[ -r /dev/kvm && -w /dev/kvm ]] || fail kvm_access

failure_gate=runner_capacity_check
if python3 - "$root" <<'PY'
import os, shutil, sys
from pathlib import Path
try:
    if shutil.disk_usage(sys.argv[1]).free < 11 * 1024**3:
        raise SystemExit(11)
    mem = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines()
                   if line.startswith('MemAvailable:')))
    if mem < 10 * 1024**2:
        raise SystemExit(12)
    if (os.cpu_count() or 0) < 4:
        raise SystemExit(13)
except SystemExit:
    raise
except BaseException:
    raise SystemExit(14)
PY
then
  :
else
  status=$?
  case "$status" in
    11) fail runner_disk_capacity ;;
    12) fail runner_memory_capacity ;;
    13) fail runner_cpu_capacity ;;
    *) fail runner_capacity_check ;;
  esac
fi

# Google SDK Manager validates official archive checksums. Reject changed package revisions.
failure_gate=sdkmanager_install
timeout 480 sdkmanager 'emulator' 'platform-tools' \
  'platforms;android-35' 'build-tools;37.0.0' \
  'system-images;android-35;google_apis;x86_64' \
  >"$root/sdk-setup.log" 2>&1 || fail sdkmanager_install
failure_gate=sdk_revision_check
if python3 - "$ANDROID_SDK_ROOT" <<'PY'
import sys, xml.etree.ElementTree as E
from pathlib import Path
sdk = Path(sys.argv[1])
try:
    checks = [('emulator/package.xml', (37, 2, 12), 21),
              ('system-images/android-35/google_apis/x86_64/package.xml', (9, 0, 0), 22)]
    for relative, expected, failure in checks:
        revision = E.parse(sdk / relative).getroot().find('localPackage/revision')
        actual = tuple(int(revision.findtext(part, '0'))
                       for part in ['major', 'minor', 'micro'])
        if actual != expected:
            raise SystemExit(failure)
except SystemExit:
    raise
except BaseException:
    raise SystemExit(23)
PY
then
  :
else
  status=$?
  case "$status" in
    21) fail emulator_revision ;;
    22) fail system_image_revision ;;
    *) fail sdk_revision_check ;;
  esac
fi
[[ -f "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
   && ! -L "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
   && -x "$ANDROID_SDK_ROOT/build-tools/37.0.0/d8" \
   && ! -L "$ANDROID_SDK_ROOT/build-tools/37.0.0/d8" ]] || fail ui_reader_sdk
failure_gate=ui_reader_build
bash tools/fgs-evidence/build-ui-reader.sh "$root/wesh-ui-reader.jar" \
  >"$root/ui-reader-build.log" 2>&1 || fail ui_reader_build
failure_gate=emulator_acceleration
emulator -accel-check >"$root/accel.log" 2>&1 || fail emulator_acceleration

for side_and_name in a:wesh-fgs-a b:wesh-fgs-b; do
  side="${side_and_name%%:*}"
  name="${side_and_name#*:}"
  failure_gate="avd_creation_$side"
  printf 'no\n' | avdmanager create avd --name "$name" --package \
    'system-images;android-35;google_apis;x86_64' --device pixel_2 \
    >"$root/$name-create.log" 2>&1 || fail "$failure_gate"
done
emulator -avd wesh-fgs-a -port 5554 -no-window -no-audio -no-snapshot -no-boot-anim \
  -gpu software -memory 2048 -cores 2 -partition-size 1024 \
  -packet-streamer-endpoint default >"$root/avd-a.log" 2>&1 &
avd_a_pid=$!
emulator -avd wesh-fgs-b -port 5556 -no-window -no-audio -no-snapshot -no-boot-anim \
  -gpu software -memory 2048 -cores 2 -partition-size 1024 \
  -packet-streamer-endpoint default >"$root/avd-b.log" 2>&1 &
avd_b_pid=$!
failure_gate=capture_attempt
timeout --signal=TERM --kill-after=15 720 python3 tools/fgs-evidence/capture.py "$root" || fail capture_attempt
