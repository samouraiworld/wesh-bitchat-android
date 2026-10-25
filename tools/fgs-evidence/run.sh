#!/usr/bin/env bash
set -euo pipefail

# This script deliberately has no physical-device fallback and never calls unscoped adb shell.
fail() { printf 'FGS prerequisite failed: %s\n' "$1" >&2; exit 1; }
[[ "${GITHUB_ACTIONS:-}" == true && "$(uname -s)" == Linux ]] || fail runner_context
root="${1:?disposable evidence directory required}"
[[ "$root" == "$RUNNER_TEMP/fgs-evidence" && -f "$root/wesh107-test-signed.apk" ]] || fail verified_test_apk
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export ANDROID_AVD_HOME="$root/avd"
export ANDROID_USER_HOME="$root/android-user"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
export PATH="$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

cleanup() {
  for serial in emulator-5554 emulator-5556; do
    timeout 8 adb -s "$serial" emu kill >/dev/null 2>&1 || true
  done
  # Only processes launched by this script, never another runner/device or a shared adb server.
  for pid in "${avd_a_pid:-}" "${avd_b_pid:-}"; do
    if [[ "$pid" =~ ^[0-9]+$ ]]; then kill "$pid" 2>/dev/null || true; fi
  done
}
trap cleanup EXIT

command -v ffmpeg >/dev/null || fail ffmpeg_missing
command -v ffprobe >/dev/null || fail ffprobe_missing
command -v sdkmanager >/dev/null || fail sdkmanager_missing
[[ -e /dev/kvm ]] || fail kvm_missing
sudo chmod a+rw /dev/kvm || fail kvm_permission_setup
[[ -r /dev/kvm && -w /dev/kvm ]] || fail kvm_access
python3 - "$root" <<'PY'
import os, shutil, sys
from pathlib import Path
if shutil.disk_usage(sys.argv[1]).free < 11 * 1024**3:
    sys.exit('Insufficient free disk for two disposable AVDs')
mem = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines()
               if line.startswith('MemAvailable:')))
if mem < 10 * 1024**2 or (os.cpu_count() or 0) < 4:
    sys.exit('This attempt requires the public 4-vCPU/16-GB runner class')
PY

# Google SDK Manager validates official archive checksums. Reject changed package revisions.
timeout 480 sdkmanager 'emulator' 'platform-tools' 'system-images;android-35;google_apis;x86_64' \
  >"$root/sdk-setup.log" 2>&1 || fail sdkmanager_install
python3 - "$ANDROID_SDK_ROOT" <<'PY'
import sys, xml.etree.ElementTree as E
from pathlib import Path
sdk = Path(sys.argv[1])
for relative, expected in [('emulator/package.xml', (37, 2, 12)),
                           ('system-images/android-35/google_apis/x86_64/package.xml', (9, 0, 0))]:
    revision = E.parse(sdk / relative).getroot().find('localPackage/revision')
    actual = tuple(int(revision.findtext(part, '0')) for part in ['major','minor','micro'])
    if actual != expected:
        sys.exit('Official SDK package revision differs from the reviewed pin')
PY
emulator -accel-check >"$root/accel.log" 2>&1 || fail emulator_acceleration

for name in wesh-fgs-a wesh-fgs-b; do
  printf 'no\n' | avdmanager create avd --name "$name" --package \
    'system-images;android-35;google_apis;x86_64' --device pixel_2 >"$root/$name-create.log" 2>&1 || fail avd_creation
done
emulator -avd wesh-fgs-a -port 5554 -no-window -no-audio -no-snapshot -no-boot-anim \
  -gpu software -memory 2048 -cores 2 -partition-size 1024 \
  -packet-streamer-endpoint default >"$root/avd-a.log" 2>&1 &
avd_a_pid=$!
emulator -avd wesh-fgs-b -port 5556 -no-window -no-audio -no-snapshot -no-boot-anim \
  -gpu software -memory 2048 -cores 2 -partition-size 1024 \
  -packet-streamer-endpoint default >"$root/avd-b.log" 2>&1 &
avd_b_pid=$!
timeout 600 python3 tools/fgs-evidence/capture.py "$root"
