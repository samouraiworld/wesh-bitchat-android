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
SCENARIO_RESULT = "SCENARIO_PASS_RECORDINGS_AWAIT_LOCAL_VALIDATION"


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
               "notPhysicalEvidence": True, "notPlaySigned": True,
               "recordingsExported": False, "finalVideoQualified": False}
    recorders = []
    start = time.monotonic()
    def event(name):
        receipt["events"].append({"step": name, "elapsedSeconds": round(time.monotonic()-start, 1)})
    try:
        with ThreadPoolExecutor(2) as pool:
            list(pool.map(lambda serial: boot_and_install(serial, root / "wesh107-test-signed.apk"), SERIALS))
        # Only a real scenario pass may export these two synthetic OS recordings for local validation.
        # Raw UI XML and runtime/SDK logs always stay on the disposable runner.
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
        receipt["result"] = SCENARIO_RESULT
    except ImeVisibilityUnknown:
        receipt["failureGate"] = "ime_visibility_unknown"
        raise
    finally:
        # On success, preserve real post-roll until the OS recorder's fixed 180-second limit.
        # This covers startup latency without weakening the conservative duration assertion.
        if receipt["result"] != SCENARIO_RESULT:
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
                status = recorder.wait(timeout=remaining if receipt["result"] == SCENARIO_RESULT else 8)
                if status != 0:
                    receipt["result"] = "RECORDING_FAILED"
            except subprocess.TimeoutExpired:
                recorder.terminate()
                receipt["result"] = "RECORDING_FAILED"
        (root / "evidence/attempt.json").write_text(json.dumps(receipt, indent=2)+"\n")
    # No recording is exported when any scenario or OS-recorder assertion above failed.
    if receipt["result"] != SCENARIO_RESULT:
        raise RuntimeError("Recorders did not complete within their fixed limit")
    recordings = ("recording-a.mp4", "recording-b.mp4")
    for serial, name in zip(SERIALS, recordings):
        adb(serial, "pull", "/sdcard/wesh-fgs.mp4", str(root / name), timeout=30)
    for name in recordings:
        path = root / name
        if not path.is_file() or path.stat().st_size == 0:
            raise RuntimeError("A required owned-emulator recording is missing")
    for name in recordings:
        (root / name).rename(root / "evidence" / name)
    receipt["recordingsExported"] = True
    # Duration, decoding, composition and visual inspection are explicitly still local gates.
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
