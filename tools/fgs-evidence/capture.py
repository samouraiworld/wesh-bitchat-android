#!/usr/bin/env python3
"""External UI automation of the unchanged release APK on two synthetic AVDs only."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as E

from prepare import digest

SERIALS = ("emulator-5554", "emulator-5556")
PACKAGE = "org.samouraiworld.wesh.android"
COMPONENT = f"{PACKAGE}/com.bitchat.android.MainActivity"
MESSAGE_A = "FGS-public-A-107"
MESSAGE_B = "FGS-public-B-107"
MESSAGE_BACKGROUND = "FGS-background-107"


class ImeVisibilityUnknown(RuntimeError):
    """A named collection gate; never include the raw input-method dump."""


def adb(serial, *args, timeout=12):
    if serial not in SERIALS:
        raise ValueError("Only the two owned emulator serials are allowed")
    result = subprocess.run(["adb", "-s", serial, *args], capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError("Scoped emulator operation failed")
    return result.stdout.decode(errors="replace")


def nodes(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/wesh-fgs-window.xml")
    xml = adb(serial, "exec-out", "cat", "/sdcard/wesh-fgs-window.xml")
    return list(E.fromstring(xml).iter("node"))


def labels(items):
    return "\n".join(n.get("text", "") + "\n" + n.get("content-desc", "") for n in items)


def select(items, label, contains=False):
    def matches(value):
        return label.casefold() in value.casefold() if contains else label.casefold() == value.casefold()
    return next((n for n in items if n.get("enabled") == "true" and
                 any(matches(n.get(key, "")) for key in ("text", "content-desc"))), None)


def tap(serial, node):
    points = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not points:
        raise ValueError("Missing UI target bounds")
    x1, y1, x2, y2 = map(int, points.groups())
    adb(serial, "shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def wait_ui(serial, label, deadline, contains=False):
    while time.monotonic() < deadline:
        target = select(nodes(serial), label, contains)
        if target is not None:
            return target
        time.sleep(0.5)
    raise RuntimeError("Expected real UI state was not reached")


def parse_ime_visibility(dump):
    states = re.findall(r"\bmInputShown=(true|false)\b", dump)
    return states[0] == "true" if len(states) == 1 else None


def hide_keyboard_if_shown(serial):
    # This raw synthetic-emulator state is read only in memory and never exported.
    shown = parse_ime_visibility(adb(serial, "shell", "dumpsys", "input_method"))
    if shown is None:
        raise ImeVisibilityUnknown()
    if shown:
        adb(serial, "shell", "input", "keyevent", "4")


def onboarding(serial, nickname, deadline):
    adb(serial, "shell", "am", "start", "-n", COMPONENT)
    named = False
    while time.monotonic() < deadline:
        items = nodes(serial)
        if select(items, "Public room") is not None:
            return
        nickname_field = select(items, "Your nickname")
        if nickname_field is not None and not named:
            # A normal text-field edit, never an identity/database/preferences fixture.
            field = next((n for n in items if n.get("class") == "android.widget.EditText"), None)
            if field is None:
                raise RuntimeError("Nickname text field unavailable")
            tap(serial, field)
            adb(serial, "shell", "input", "keyevent", "123")
            adb(serial, "shell", "input", "keyevent", *(["67"] * 40))
            adb(serial, "shell", "input", "text", nickname)
            hide_keyboard_if_shown(serial)
            named = True
            continue
        # Only actual product actions and Android permission buttons; no arbitrary dialog dismissal.
        for label in ("Start", "Continue", "Allow notifications", "Allow", "While using the app",
                      "Skip for Now", "Turn on Bluetooth"):
            target = select(items, label)
            if target is not None:
                tap(serial, target)
                break
        time.sleep(0.5)
    raise RuntimeError("Onboarding did not complete within the bounded attempt")


def send(serial, message, deadline):
    # Assertions later inspect the opposite app, not this local outgoing bubble.
    target = wait_ui(serial, "Public message — everyone around", deadline)
    tap(serial, target)
    adb(serial, "shell", "input", "text", message)
    tap(serial, wait_ui(serial, "Send in public", deadline))
    confirmed = False
    while time.monotonic() < deadline:
        items = nodes(serial)
        state = public_send_state(items, message)
        if state == "confirm" and not confirmed:
            confirmation = select(items, "Send in public")
            if confirmation is None:
                raise RuntimeError("Public disclosure confirmation action is unavailable")
            tap(serial, confirmation)
            confirmed = True
        elif state == "submitted":
            # Actual draft clear plus local bubble, followed by opposite-device receipt in main().
            return
        time.sleep(0.2)
    raise RuntimeError("Real public send/disclosure did not complete")


def public_send_state(items, message):
    if select(items, "Send to the public room?") is not None:
        return "confirm"
    editable = [n for n in items if n.get("class") == "android.widget.EditText"]
    if editable and all(not n.get("text", "") for n in editable) and message in labels(items):
        return "submitted"
    return "pending"


def fgs_active(serial):
    state = adb(serial, "shell", "dumpsys", "activity", "services",
                f"{PACKAGE}/com.bitchat.android.service.MeshForegroundService")
    return "MeshForegroundService" in state and "isForeground=true" in state


def boot_and_install(serial, apk):
    deadline = time.monotonic() + 210
    while time.monotonic() < deadline:
        try:
            if adb(serial, "shell", "getprop", "sys.boot_completed", timeout=5).strip() == "1":
                break
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    else:
        raise RuntimeError("Emulator boot timeout")
    if adb(serial, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Target is not an emulator")
    if adb(serial, "shell", "getprop", "ro.build.version.sdk").strip() != "35":
        raise RuntimeError("Unexpected API image")
    adb(serial, "shell", "input", "keyevent", "82")
    adb(serial, "shell", "settings", "put", "system", "screen_off_timeout", "600000")
    # The emulator locale is English; no location providers or coordinates are accessed.
    adb(serial, "shell", "svc", "bluetooth", "enable")
    adb(serial, "install", "--no-streaming", str(apk), timeout=50)


def main(root):
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("Disposable GitHub runner only")
    receipt = {"result": "NOT_COMPLETED", "events": [], "environment": "Two API35 x86_64 AVDs; netsim simulated BLE",
               "emulator": "37.2.12", "systemImageRevision": 9, "evidenceSource": os.environ["EVIDENCE_HEAD"],
               "notPhysicalEvidence": True, "notPlaySigned": True, "videoExported": False}
    recorders = []
    start = time.monotonic()
    def event(name):
        receipt["events"].append({"step": name, "elapsedSeconds": round(time.monotonic()-start, 1)})
    try:
        with ThreadPoolExecutor(2) as pool:
            list(pool.map(lambda serial: boot_and_install(serial, root / "wesh107-test-signed.apk"), SERIALS))
        # Raw recordings and UI XML stay on disposable runner; only a passing derived clip is uploaded.
        # The scenario clock starts BEFORE either recorder command, never after readiness waits.
        # Requiring both durations to cover this conservative origin cannot hide early truncation.
        start = time.monotonic()
        for serial in SERIALS:
            recorders.append(subprocess.Popen(["adb", "-s", serial, "shell", "screenrecord",
                "--time-limit", "180", "--bit-rate", "2000000", "/sdcard/wesh-fgs.mp4"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        for serial in SERIALS:
            recorder_ready = time.monotonic()+8
            while time.monotonic() < recorder_ready:
                try:
                    if adb(serial, "shell", "pidof", "screenrecord").strip():
                        break
                except RuntimeError:
                    pass
                time.sleep(0.2)
            else:
                raise RuntimeError("OS screen recorder did not start")
        deadline = start + 165
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(onboarding, serial, nickname, start+50)
                       for serial, nickname in zip(SERIALS, ("FGS-A", "FGS-B"))]
            for future in futures:
                future.result()
        event("actual_onboarding_permissions_completed")
        # Genuine reciprocal discovery is required before entering the public room.
        for serial in SERIALS:
            wait_ui(serial, "1 phone in range", min(deadline, start+105), contains=True)
        event("both_apps_display_real_peer")
        for serial in SERIALS:
            tap(serial, wait_ui(serial, "Public room", deadline))
        send(SERIALS[0], MESSAGE_A, deadline)
        wait_ui(SERIALS[1], MESSAGE_A, deadline, contains=True)
        send(SERIALS[1], MESSAGE_B, deadline)
        wait_ui(SERIALS[0], MESSAGE_B, deadline, contains=True)
        event("public_messages_received_in_both_directions")
        adb(SERIALS[0], "shell", "input", "keyevent", "3")
        if not fgs_active(SERIALS[0]):
            raise RuntimeError("Actual foreground service is not active in background")
        adb(SERIALS[0], "shell", "cmd", "statusbar", "expand-notifications")
        notification = wait_ui(SERIALS[0], "Mesh running", deadline, contains=True)
        event("background_connected_device_notification_visible")
        send(SERIALS[1], MESSAGE_BACKGROUND, deadline)
        event("public_message_sent_while_other_app_in_background")
        # Re-read current bounds: a new notification could have moved the persistent FGS row.
        notification = wait_ui(SERIALS[0], "Mesh running", deadline, contains=True)
        tap(SERIALS[0], notification)
        wait_ui(SERIALS[0], MESSAGE_BACKGROUND, deadline, contains=True)
        # This proves visibility after resume, not the exact instant of background receipt.
        event("public_message_visible_after_resume_via_fgs_notification")
        adb(SERIALS[0], "shell", "input", "keyevent", "3")
        adb(SERIALS[0], "shell", "cmd", "statusbar", "expand-notifications")
        items = nodes(SERIALS[0])
        quit_action = select(items, "Quit Wesh")
        if quit_action is None:
            expand = select(items, "Expand")
            if expand is not None:
                tap(SERIALS[0], expand)
            quit_action = wait_ui(SERIALS[0], "Quit Wesh", deadline)
        tap(SERIALS[0], quit_action)
        while fgs_active(SERIALS[0]) and time.monotonic() < deadline:
            time.sleep(0.5)
        if fgs_active(SERIALS[0]) or select(nodes(SERIALS[0]), "Mesh running", contains=True) is not None:
            raise RuntimeError("Quit did not remove the foreground service/notification")
        event("quit_action_stopped_service_and_removed_notification")
        if time.monotonic() >= deadline:
            raise RuntimeError("Scenario exceeded recording budget")
        receipt["result"] = "PASS_SIMULATED_BLE_ONLY"
    except ImeVisibilityUnknown:
        receipt["failureGate"] = "ime_visibility_unknown"
        raise
    finally:
        # On success, preserve real post-roll until the OS recorder's fixed 180-second limit.
        # This covers startup latency without weakening the conservative duration assertion.
        if receipt["result"] != "PASS_SIMULATED_BLE_ONLY":
            for serial in SERIALS:
                try:
                    pids = adb(serial, "shell", "pidof", "screenrecord").strip().split()
                    if pids and all(pid.isdigit() for pid in pids):
                        adb(serial, "shell", "kill", "-2", *pids)
                except (RuntimeError, subprocess.TimeoutExpired):
                    pass
        for recorder in recorders:
            try:
                remaining = max(0.1, start + 190 - time.monotonic())
                recorder.wait(timeout=remaining if receipt["result"] == "PASS_SIMULATED_BLE_ONLY" else 8)
            except subprocess.TimeoutExpired:
                recorder.terminate()
                receipt["result"] = "RECORDING_FAILED"
        (root / "evidence/attempt.json").write_text(json.dumps(receipt, indent=2)+"\n")
    # No video is exported when any assertion above failed.
    if receipt["result"] != "PASS_SIMULATED_BLE_ONLY":
        raise RuntimeError("Recorders did not complete within their fixed limit")
    for serial, name in zip(SERIALS, ("a.mp4", "b.mp4")):
        adb(serial, "pull", "/sdcard/wesh-fgs.mp4", str(root / name), timeout=30)
    durations = []
    for name in ("a.mp4", "b.mp4"):
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", str(root/name)],
            capture_output=True, text=True, timeout=10)
        if probe.returncode:
            raise RuntimeError("OS recording is not playable")
        durations.append(float(probe.stdout.strip()))
    if min(durations) < receipt["events"][-1]["elapsedSeconds"]:
        raise RuntimeError("Recording does not cover the final Quit action")
    clip = root / "assembled.mp4"
    overlay = ("[0:v]setpts=PTS-STARTPTS,scale=720:1280[a];"
               "[1:v]setpts=PTS-STARTPTS,scale=720:1280[b];"
               "[a][b]hstack=shortest=1,pad=1440:1360:0:0:black,"
               "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:"
               "text='Wesh 107 - two emulators - simulated BLE netsim':fontcolor=white:fontsize=24:x=12:y=1290,"
               "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf:"
               "text='Canonical APK payload - disposable test signature':fontcolor=white:fontsize=24:x=12:y=1328")
    result = subprocess.run(["ffmpeg", "-y", "-i", str(root/"a.mp4"), "-i", str(root/"b.mp4"),
        "-filter_complex", overlay, "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
        "-an", "-map_metadata", "-1", str(clip)], capture_output=True, timeout=90)
    if result.returncode:
        clip.unlink(missing_ok=True)
        raise RuntimeError("Video assembly failed")
    clip.rename(root / "evidence/fgs-simulated-ble.mp4")
    receipt["videoExported"] = True
    receipt["recordingDurationsSeconds"] = durations
    (root / "evidence/attempt.json").write_text(json.dumps(receipt, indent=2)+"\n")
    (root / "evidence/SHA256SUMS").write_text("".join(
        f"{digest(path)}  {path.name}\n" for path in sorted((root/"evidence").iterdir()) if path.is_file()))


if __name__ == "__main__":
    try:
        main(Path(sys.argv[1]))
    except ImeVisibilityUnknown:
        sys.exit("FGS UI gate failed: ime_visibility_unknown; no Back or passing video exported")
    except Exception:
        sys.exit("Real emulator attempt did not complete; no passing video is exported.")
