#!/usr/bin/env bash
set -euo pipefail

# Two disposable AVDs and one canonical product flow; no recorder or artifact export.
fail() { printf 'FAIL gate=%s elapsedSeconds=0.0\n' "$1" >&2; exit 1; }
[[ "${GITHUB_ACTIONS:-}" == true && "$(uname -s)" == Linux ]] || fail runner_context
[[ "$#" == 1 ]] || fail owned_root
root="$1"
[[ "$root" == "$RUNNER_TEMP/fgs-scenario" && -f "$root/wesh108-test-signed.apk" ]] \
  || fail verified_test_apk
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk_root" && -d "$sdk_root" ]] || fail sdk_root
export ANDROID_SDK_ROOT="$sdk_root"
export ANDROID_AVD_HOME="$root/avd"
export ANDROID_USER_HOME="$root/android-user"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME" 2>/dev/null || fail owned_root_setup
export PATH="$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

cleanup() {
  for serial in emulator-5554 emulator-5556; do
    timeout 8 adb -s "$serial" emu kill >/dev/null 2>&1 || true
  done
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
python3 - "$root" <<'PY' || fail runner_capacity
import os, shutil, sys
try:
    enough_disk = shutil.disk_usage(sys.argv[1]).free >= 11 * 1024**3
    with open('/proc/meminfo') as stream:
        memory = int(next(line.split()[1] for line in stream
                          if line.startswith('MemAvailable:')))
    enough_memory = memory >= 10 * 1024**2
    enough_cpu = (os.cpu_count() or 0) >= 4
except BaseException:
    raise SystemExit(1)
if not (enough_disk and enough_memory and enough_cpu):
    raise SystemExit(1)
PY

timeout 480 sdkmanager 'emulator' 'platform-tools' \
  'platforms;android-35' 'build-tools;37.0.0' \
  'system-images;android-35;google_apis;x86_64' >"$root/sdk-setup.log" 2>&1 \
  || fail sdkmanager_install
python3 - "$ANDROID_SDK_ROOT" <<'PY' || fail sdk_revision
import sys, xml.etree.ElementTree as E
from pathlib import Path
try:
    sdk = Path(sys.argv[1])
    for relative, expected in [('emulator/package.xml', (37, 2, 12)),
                               ('system-images/android-35/google_apis/x86_64/package.xml', (9, 0, 0))]:
        revision = E.parse(sdk / relative).getroot().find('localPackage/revision')
        actual = tuple(int(revision.findtext(part, '0'))
                       for part in ['major','minor','micro'])
        if actual != expected:
            raise ValueError
except BaseException:
    raise SystemExit(1)
PY
[[ -f "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
   && ! -L "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
   && -x "$ANDROID_SDK_ROOT/build-tools/37.0.0/d8" \
   && ! -L "$ANDROID_SDK_ROOT/build-tools/37.0.0/d8" ]] || fail ui_reader_sdk
bash tools/fgs-evidence/build-ui-reader.sh "$root/wesh-ui-reader.jar" \
  >"$root/ui-reader-build.log" 2>&1 || fail ui_reader_build
emulator -accel-check >"$root/accel.log" 2>&1 || fail emulator_acceleration

for name in wesh-scenario-a wesh-scenario-b; do
  printf 'no\n' | avdmanager create avd --name "$name" --package \
    'system-images;android-35;google_apis;x86_64' --device pixel_2 \
    >"$root/$name-create.log" 2>&1 || fail avd_creation
done
emulator -avd wesh-scenario-a -port 5554 -no-window -no-audio -no-snapshot \
  -no-boot-anim -gpu software -memory 2048 -cores 2 -partition-size 1024 \
  -packet-streamer-endpoint default >"$root/avd-a.log" 2>&1 &
avd_a_pid=$!
emulator -avd wesh-scenario-b -port 5556 -no-window -no-audio -no-snapshot \
  -no-boot-anim -gpu software -memory 2048 -cores 2 -partition-size 1024 \
  -packet-streamer-endpoint default >"$root/avd-b.log" 2>&1 &
avd_b_pid=$!

set +e
timeout --signal=TERM --kill-after=15 720 \
  python3 tools/fgs-evidence/scenario_check.py "$root"
status=$?
set -e
if [[ "$status" == 124 || "$status" == 137 ]]; then
  printf 'FAIL gate=controller_timeout elapsedSeconds=720.0\n' >&2
  PYTHONPATH=tools/fgs-evidence python3 -c \
    'import json; from onboarding_check import empty_trace_payload; print("SCENARIO_TRACE "+json.dumps(empty_trace_payload(),sort_keys=True,separators=(",",":")))' \
    >&2
fi
exit "$status"
