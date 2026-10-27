#!/usr/bin/env python3
"""External UI automation of the unchanged release APK on two synthetic AVDs only."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from threading import Lock
import time
import xml.etree.ElementTree as E

from prepare import digest

SERIALS = ("emulator-5554", "emulator-5556")
SIDES = {SERIALS[0]: "a", SERIALS[1]: "b"}
PACKAGE = "org.samouraiworld.wesh.android"
COMPONENT = f"{PACKAGE}/com.bitchat.android.MainActivity"
MESSAGE_A = "FGS-public-A-107"
MESSAGE_B = "FGS-public-B-107"
MESSAGE_BACKGROUND = "FGS-background-107"
SCENARIO_RESULT = "SCENARIO_PASS_RECORDINGS_AWAIT_LOCAL_VALIDATION"
CAPTURE_SOURCE = "ANDROID_EMULATOR_HOST_PHYSICAL_DISPLAY_WEBM"
RECORDING_LIMIT_SECONDS = 180
SCENARIO_BUDGET_SECONDS = 165
UI_SNAPSHOT_BUDGET_SECONDS = 26
UI_DUMP_COMMAND_SECONDS = 13
UI_DUMP_ATTEMPTS = 2
UI_CLEANUP_RESERVE_SECONDS = 1
UI_FILE_COMMAND_SECONDS = 2

SERIAL_PHASES = frozenset({
    "boot_timeout", "emulator_identity", "api_image", "device_setup",
    "bluetooth_on_timeout", "install", "package_verify", "activity_launch",
    "activity_resume_timeout", "ui_dump_command_timeout", "ui_dump_command_error",
    "ui_dump_stale", "ui_dump_output_missing", "ui_dump_read", "ui_dump_parse",
    "ui_dump_cleanup", "ui_action", "nickname_field",
    "ime_visibility_unknown", "onboarding_welcome", "onboarding_bluetooth",
    "onboarding_notifications", "onboarding_permission", "onboarding_auxiliary",
    "onboarding_unknown", "fgs_timeout", "peer_discovery_timeout",
    "public_room_open", "message_composer", "message_submit",
    "message_confirmation", "message_receive", "notification_expand",
    "notification_visible", "notification_open", "quit_action", "quit_timeout",
    "recorded_relaunch", "host_recorder_capability", "host_recorder_start",
    "host_recording_file",
})
GLOBAL_GATES = frozenset({"unclassified_controller_failure", "scenario_budget", "evidence_export"})
PREFLIGHT_STEPS = (
    "boot_install_bluetooth_ready", "actual_onboarding_permissions_completed",
    "foreground_services_active", "both_apps_display_real_peer",
    "services_stopped_for_recorded_relaunch",
)
SCENARIO_STEPS = (
    "actual_onboarding_permissions_completed", "both_apps_display_real_peer",
    "public_messages_received_in_both_directions", "background_connected_device_notification_visible",
    "public_message_sent_while_other_app_in_background", "public_message_visible_after_resume_via_fgs_notification",
    "quit_action_stopped_service_and_removed_notification",
)

_UI_DUMP_SEQUENCE_LOCK = Lock()
_UI_DUMP_SEQUENCE = {serial: 0 for serial in SERIALS}


def gate_name(phase, serial=None):
    if serial is None:
        if phase not in GLOBAL_GATES:
            raise ValueError("Unallowlisted global failure gate")
        return phase
    if serial not in SIDES or phase not in SERIAL_PHASES:
        raise ValueError("Unallowlisted serial failure gate")
    return f"{phase}_{SIDES[serial]}"


class GateError(RuntimeError):
    """A non-sensitive, allowlisted failure classification for the exported receipt."""

    def __init__(self, phase, serial=None):
        self.gate = gate_name(phase, serial)
        super().__init__(self.gate)


class ImeVisibilityUnknown(GateError):
    def __init__(self, serial):
        super().__init__("ime_visibility_unknown", serial)


def adb(serial, *args, timeout=12):
    if serial not in SERIALS:
        raise ValueError("Only the two owned emulator serials are allowed")
    result = subprocess.run(["adb", "-s", serial, *args], capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError("Scoped emulator operation failed")
    return result.stdout.decode(errors="replace")


def gated(serial, phase, operation):
    try:
        return operation()
    except GateError:
        raise
    except (RuntimeError, ValueError, subprocess.TimeoutExpired, E.ParseError):
        raise GateError(phase, serial) from None


def bounded_timeout(deadline, cap):
    remaining = deadline - time.monotonic()
    return None if remaining <= 0 else max(0.1, min(cap, remaining))


def deadline_adb(serial, phase, deadline, *args, cap=12):
    timeout = bounded_timeout(deadline, cap)
    if timeout is None:
        raise GateError(phase, serial)
    return gated(serial, phase, lambda: adb(serial, *args, timeout=timeout))


def bounded_sleep(deadline, cap):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        return False
    time.sleep(min(cap, remaining))
    return True


def ui_dump_path(serial):
    """Return a controller-owned, side-specific path unique in this process."""
    if serial not in SIDES:
        raise ValueError("Only the two owned emulator serials are allowed")
    with _UI_DUMP_SEQUENCE_LOCK:
        _UI_DUMP_SEQUENCE[serial] += 1
        sequence = _UI_DUMP_SEQUENCE[serial]
    return f"/data/local/tmp/wesh-fgs-window-{SIDES[serial]}-{sequence:08d}.xml"


def ui_timeout(deadline, cap):
    """Unlike general adb deadlines, never round a UI budget past its deadline."""
    remaining = deadline - time.monotonic()
    return None if remaining <= 0 else min(cap, remaining)


def ui_adb(serial, deadline, timeout_phase, error_phase, *args, cap):
    timeout = ui_timeout(deadline, cap)
    if timeout is None:
        raise GateError(timeout_phase, serial)
    try:
        return adb(serial, *args, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise GateError(timeout_phase, serial) from None
    except (RuntimeError, ValueError):
        raise GateError(error_phase, serial) from None


def ui_dump_attempt(serial, path, deadline):
    """Create, read and remove one fresh hierarchy without exporting its contents."""
    work_deadline = deadline - UI_CLEANUP_RESERVE_SECONDS
    try:
        ui_adb(serial, work_deadline, "ui_dump_cleanup", "ui_dump_cleanup",
               "shell", "rm", "-f", path, cap=UI_FILE_COMMAND_SECONDS)
        ui_adb(serial, work_deadline, "ui_dump_stale", "ui_dump_stale",
               "shell", "test", "!", "-e", path, cap=UI_FILE_COMMAND_SECONDS)
        ui_adb(serial, work_deadline, "ui_dump_command_timeout", "ui_dump_command_error",
               "shell", "uiautomator", "dump", path, cap=UI_DUMP_COMMAND_SECONDS)
        ui_adb(serial, work_deadline, "ui_dump_output_missing", "ui_dump_output_missing",
               "shell", "test", "-s", path, cap=UI_FILE_COMMAND_SECONDS)
        xml = ui_adb(serial, work_deadline, "ui_dump_read", "ui_dump_read",
                     "exec-out", "cat", path, cap=UI_DUMP_COMMAND_SECONDS)
        if not xml.strip():
            raise GateError("ui_dump_output_missing", serial)
        try:
            root = E.fromstring(xml)
        except E.ParseError:
            raise GateError("ui_dump_parse", serial) from None
        items = list(root.iter("node"))
        if root.tag != "hierarchy" or not items:
            raise GateError("ui_dump_parse", serial)
        return items
    finally:
        ui_adb(serial, deadline, "ui_dump_cleanup", "ui_dump_cleanup",
               "shell", "rm", "-f", path, cap=UI_CLEANUP_RESERVE_SECONDS)


def nodes(serial, deadline):
    """Take at most two fresh snapshots inside a 26-second/caller deadline."""
    snapshot_deadline = min(deadline, time.monotonic() + UI_SNAPSHOT_BUDGET_SECONDS)
    if snapshot_deadline - time.monotonic() <= UI_CLEANUP_RESERVE_SECONDS:
        raise GateError("ui_dump_command_timeout", serial)
    last_failure = None
    for attempt in range(UI_DUMP_ATTEMPTS):
        if time.monotonic() >= snapshot_deadline:
            break
        path = ui_dump_path(serial)
        try:
            return ui_dump_attempt(serial, path, snapshot_deadline)
        except GateError as failure:
            if failure.gate == gate_name("ui_dump_cleanup", serial):
                raise
            last_failure = failure
            if attempt + 1 < UI_DUMP_ATTEMPTS:
                bounded_sleep(snapshot_deadline, 0.25)
    if last_failure is not None:
        raise last_failure
    raise GateError("ui_dump_command_timeout", serial)


def labels(items):
    return "\n".join(n.get("text", "") + "\n" + n.get("content-desc", "") for n in items)


def select(items, label, contains=False):
    def matches(value):
        return label.casefold() in value.casefold() if contains else label.casefold() == value.casefold()
    return next((n for n in items if n.get("enabled") == "true" and
                 any(matches(n.get(key, "")) for key in ("text", "content-desc"))), None)


def tap(serial, node, phase="ui_action", deadline=None):
    points = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not points:
        raise GateError(phase, serial)
    x1, y1, x2, y2 = map(int, points.groups())
    args = ("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    if deadline is None:
        gated(serial, phase, lambda: adb(serial, *args))
    else:
        deadline_adb(serial, phase, deadline, *args)


def wait_ui(serial, label, deadline, contains=False, failure_phase="ui_action"):
    while time.monotonic() < deadline:
        target = select(nodes(serial, deadline), label, contains)
        if target is not None:
            return target
        if not bounded_sleep(deadline, 0.4):
            break
    raise GateError(failure_phase, serial)


def parse_ime_visibility(dump):
    states = re.findall(r"\bmInputShown=(true|false)\b", dump)
    return states[0] == "true" if len(states) == 1 else None


def hide_keyboard_if_shown(serial, deadline=None):
    try:
        if deadline is None:
            dump = adb(serial, "shell", "dumpsys", "input_method")
        else:
            dump = deadline_adb(serial, "ime_visibility_unknown", deadline,
                                "shell", "dumpsys", "input_method")
        shown = parse_ime_visibility(dump)
    except (RuntimeError, subprocess.TimeoutExpired):
        raise ImeVisibilityUnknown(serial) from None
    if shown is None:
        raise ImeVisibilityUnknown(serial)
    if shown:
        if deadline is None:
            gated(serial, "onboarding_welcome", lambda: adb(serial, "shell", "input", "keyevent", "4"))
        else:
            deadline_adb(serial, "onboarding_welcome", deadline, "shell", "input", "keyevent", "4")


def activity_resumed(serial, timeout=8):
    state = adb(serial, "shell", "dumpsys", "activity", "activities", timeout=timeout)
    return any(PACKAGE in line and ("mResumedActivity" in line or "topResumedActivity" in line)
               for line in state.splitlines())


def wait_activity_resumed(serial, deadline):
    while time.monotonic() < deadline:
        try:
            timeout = bounded_timeout(deadline, 8)
            if timeout is None:
                break
            if activity_resumed(serial, timeout):
                return
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        if not bounded_sleep(deadline, 0.4):
            break
    raise GateError("activity_resume_timeout", serial)


def onboarding(serial, nickname, deadline):
    deadline_adb(serial, "activity_launch", deadline, "shell", "am", "start", "-n", COMPONENT)
    wait_activity_resumed(serial, min(deadline, time.monotonic() + 20))
    named = False
    last_phase = "onboarding_unknown"
    while time.monotonic() < deadline:
        items = nodes(serial, deadline)
        if select(items, "Public room") is not None:
            return
        nickname_label = select(items, "Your nickname")
        if nickname_label is not None:
            last_phase = "onboarding_welcome"
            if not named:
                field = next((n for n in items if n.get("class") == "android.widget.EditText"), None)
                if field is None:
                    raise GateError("nickname_field", serial)
                tap(serial, field, "onboarding_welcome", deadline)
                deadline_adb(serial, "onboarding_welcome", deadline, "shell", "input", "keyevent", "123")
                deadline_adb(serial, "onboarding_welcome", deadline, "shell", "input", "keyevent", *("67",) * 40)
                deadline_adb(serial, "onboarding_welcome", deadline, "shell", "input", "text", nickname)
                hide_keyboard_if_shown(serial, deadline)
                named = True
                continue
            start = select(items, "Start")
            if start is not None:
                tap(serial, start, "onboarding_welcome", deadline)
                bounded_sleep(deadline, 0.4)
                continue

        actions = (
            ("Allow notifications", "onboarding_notifications"),
            ("While using the app", "onboarding_permission"),
            ("Allow", "onboarding_permission"),
            ("Continue", "onboarding_bluetooth"),
            ("Turn on Bluetooth", "onboarding_bluetooth"),
            ("Skip for Now", "onboarding_auxiliary"),
        )
        acted = False
        for label, phase in actions:
            target = select(items, label)
            if target is not None:
                last_phase = phase
                tap(serial, target, phase, deadline)
                acted = True
                break
        if not acted:
            last_phase = "onboarding_unknown"
        if not bounded_sleep(deadline, 0.4):
            break
    raise GateError(last_phase, serial)


def send(serial, message, deadline):
    target = wait_ui(serial, "Public message — everyone around", deadline,
                     failure_phase="message_composer")
    tap(serial, target, "message_composer", deadline)
    deadline_adb(serial, "message_composer", deadline, "shell", "input", "text", message)
    submit = wait_ui(serial, "Send in public", deadline, failure_phase="message_submit")
    tap(serial, submit, "message_submit", deadline)
    confirmed = False
    while time.monotonic() < deadline:
        items = nodes(serial, deadline)
        state = public_send_state(items, message)
        if state == "confirm" and not confirmed:
            confirmation = select(items, "Send in public")
            if confirmation is None:
                raise GateError("message_confirmation", serial)
            tap(serial, confirmation, "message_confirmation", deadline)
            confirmed = True
        elif state == "submitted":
            return
        if not bounded_sleep(deadline, 0.2):
            break
    raise GateError("message_submit", serial)


def public_send_state(items, message):
    if select(items, "Send to the public room?") is not None:
        return "confirm"
    editable = [n for n in items if n.get("class") == "android.widget.EditText"]
    if editable and all(not n.get("text", "") for n in editable) and message in labels(items):
        return "submitted"
    return "pending"


def fgs_active(serial, timeout=12):
    state = adb(serial, "shell", "dumpsys", "activity", "services",
                f"{PACKAGE}/com.bitchat.android.service.MeshForegroundService", timeout=timeout)
    return "MeshForegroundService" in state and "isForeground=true" in state


def wait_fgs_active(serial, deadline):
    while time.monotonic() < deadline:
        try:
            timeout = bounded_timeout(deadline, 8)
            if timeout is None:
                break
            if fgs_active(serial, timeout):
                return
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        if not bounded_sleep(deadline, 0.5):
            break
    raise GateError("fgs_timeout", serial)


def wait_bluetooth_on(serial, deadline):
    while time.monotonic() < deadline:
        try:
            timeout = bounded_timeout(deadline, 5)
            if timeout is None:
                break
            if adb(serial, "shell", "settings", "get", "global", "bluetooth_on", timeout=timeout).strip() == "1":
                return
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        if not bounded_sleep(deadline, 0.5):
            break
    raise GateError("bluetooth_on_timeout", serial)


def boot_and_install(serial, apk):
    deadline = time.monotonic() + 240
    booted = False
    while time.monotonic() < deadline:
        try:
            timeout = bounded_timeout(deadline, 5)
            if timeout is None:
                break
            if adb(serial, "shell", "getprop", "sys.boot_completed", timeout=timeout).strip() == "1":
                booted = True
                break
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        if not bounded_sleep(deadline, 2):
            break
    if not booted:
        raise GateError("boot_timeout", serial)
    if gated(serial, "emulator_identity", lambda: adb(serial, "shell", "getprop", "ro.kernel.qemu")).strip() != "1":
        raise GateError("emulator_identity", serial)
    if gated(serial, "api_image", lambda: adb(serial, "shell", "getprop", "ro.build.version.sdk")).strip() != "35":
        raise GateError("api_image", serial)
    gated(serial, "device_setup", lambda: adb(serial, "shell", "input", "keyevent", "82"))
    gated(serial, "device_setup", lambda: adb(serial, "shell", "settings", "put", "system", "screen_off_timeout", "600000"))
    gated(serial, "device_setup", lambda: adb(serial, "shell", "svc", "bluetooth", "enable"))
    wait_bluetooth_on(serial, min(deadline, time.monotonic() + 30))
    gated(serial, "install", lambda: adb(serial, "install", "--no-streaming", str(apk), timeout=60))
    package_path = gated(serial, "package_verify", lambda: adb(serial, "shell", "pm", "path", PACKAGE))
    if not package_path.strip().startswith("package:"):
        raise GateError("package_verify", serial)


def quit_via_notification(serial, deadline):
    deadline_adb(serial, "notification_expand", deadline, "shell", "input", "keyevent", "3")
    timeout = bounded_timeout(deadline, 8)
    if timeout is None or not fgs_active(serial, timeout):
        raise GateError("fgs_timeout", serial)
    deadline_adb(serial, "notification_expand", deadline,
                 "shell", "cmd", "statusbar", "expand-notifications")
    items = nodes(serial, deadline)
    quit_action = select(items, "Quit Wesh")
    if quit_action is None:
        expand = select(items, "Expand")
        if expand is not None:
            tap(serial, expand, "notification_expand", deadline)
        quit_action = wait_ui(serial, "Quit Wesh", deadline, failure_phase="quit_action")
    tap(serial, quit_action, "quit_action", deadline)
    service_stopped = False
    while time.monotonic() < deadline:
        try:
            timeout = bounded_timeout(deadline, 8)
            if timeout is None or not fgs_active(serial, timeout):
                service_stopped = timeout is not None
                break
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        if not bounded_sleep(deadline, 0.5):
            break
    if not service_stopped:
        raise GateError("quit_timeout", serial)
    if select(nodes(serial, deadline), "Mesh running", contains=True) is not None:
        raise GateError("quit_timeout", serial)


def require_host_recorder(serial, deadline):
    help_text = deadline_adb(serial, "host_recorder_capability", deadline,
                             "emu", "help", "screenrecord")
    normalized = help_text.casefold()
    if "screenrecord" not in normalized or "webm" not in normalized or "ok" not in normalized.split():
        raise GateError("host_recorder_capability", serial)


def start_host_recorder(serial, output, deadline):
    expected = f"recording-{SIDES[serial]}.webm"
    expected_avd = f"wesh-fgs-{SIDES[serial]}.avd"
    if (not output.is_absolute() or output.name != expected or output.parent.name != "console_out" or
            output.parent.parent.name != expected_avd):
        raise GateError("host_recorder_start", serial)
    response = deadline_adb(
        serial, "host_recorder_start", deadline, "emu", "screenrecord", "start",
        "--time-limit", str(RECORDING_LIMIT_SECONDS), output.name, cap=8,
    )
    if "ok" not in response.casefold().split():
        raise GateError("host_recorder_start", serial)


def wait_host_recordings(recordings, last_recorder_start):
    remaining = last_recorder_start + RECORDING_LIMIT_SECONDS + 10 - time.monotonic()
    if remaining > 0:
        time.sleep(remaining)
    for serial, path in zip(SERIALS, recordings):
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            raise GateError("host_recording_file", serial)


def export_host_recordings(root, recordings, receipt):
    evidence = root / "evidence"
    staging = root / "evidence-ready"
    backup = root / "evidence-failure-receipts"
    staged_recordings = [staging / path.name for path in recordings]
    allowed_names = ("recording-a.webm", "recording-b.webm", "provenance.json",
                     "attempt.json", "SHA256SUMS")
    backup_moved = False

    def remove_owned(directory):
        for name in allowed_names:
            try:
                (directory / name).unlink(missing_ok=True)
            except OSError:
                pass
        try:
            directory.rmdir()
        except OSError:
            pass

    try:
        staging.mkdir()
        for path, destination in zip(recordings, staged_recordings):
            path.rename(destination)
        (staging / "provenance.json").write_bytes((evidence / "provenance.json").read_bytes())
        receipt["recordingsExported"] = True
        receipt["failureGate"] = None
        (staging / "attempt.json").write_text(json.dumps(receipt, indent=2)+"\n")
        (staging / "SHA256SUMS").write_text("".join(
            f"{digest(path)}  {path.name}\n"
            for path in sorted(staging.iterdir()) if path.name != "SHA256SUMS"))
        if {path.name for path in staging.iterdir()} != set(allowed_names):
            raise OSError("Incomplete owned evidence staging")
        evidence.rename(backup)
        backup_moved = True
        staging.rename(evidence)
    except OSError:
        # Raw WebMs remain outside evidence until one atomic directory swap makes the
        # complete, checksummed manifest visible.
        if backup_moved and not evidence.exists() and backup.exists():
            try:
                backup.rename(evidence)
            except OSError:
                pass
        for path in recordings:
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
        remove_owned(staging)
        try:
            receipt["result"] = "NOT_COMPLETED"
            receipt["recordingsExported"] = False
            receipt["failureGate"] = gate_name("evidence_export")
            (evidence / "attempt.json").write_text(json.dumps(receipt, indent=2)+"\n")
        except OSError:
            pass
        raise GateError("evidence_export") from None
    # Old failure receipts never enter the uploaded evidence directory and contain no WebM.
    remove_owned(backup)


def main(root):
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("Disposable GitHub runner only")
    receipt = {
        "result": "NOT_COMPLETED", "failureGate": None, "preflightEvents": [], "events": [],
        "environment": "Two API35 x86_64 AVDs; netsim simulated BLE",
        "emulator": "37.2.12", "systemImageRevision": 9, "evidenceSource": os.environ["EVIDENCE_HEAD"],
        "captureSource": CAPTURE_SOURCE,
        "notPhysicalEvidence": True, "notPlaySigned": True,
        "recordingsExported": False, "finalVideoQualified": False,
    }
    recordings = [root / "avd" / f"wesh-fgs-{side}.avd" / "console_out" /
                  f"recording-{side}.webm" for side in ("a", "b")]
    video_start = None
    last_recorder_start = None
    attempt_start = time.monotonic()

    def preflight_event(name):
        if name not in PREFLIGHT_STEPS:
            raise ValueError("Unallowlisted preflight event")
        receipt["preflightEvents"].append({"step": name, "elapsedSeconds": round(time.monotonic()-attempt_start, 1)})

    def event(name, elapsed=None):
        if name not in SCENARIO_STEPS:
            raise ValueError("Unallowlisted scenario event")
        value = time.monotonic()-video_start if elapsed is None else elapsed
        receipt["events"].append({"step": name, "elapsedSeconds": round(value, 1)})

    try:
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(boot_and_install, serial, root / "wesh107-test-signed.apk") for serial in SERIALS]
            for future in futures:
                future.result()
        preflight_event("boot_install_bluetooth_ready")

        onboarding_deadline = time.monotonic() + 90
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(onboarding, serial, nickname, onboarding_deadline)
                       for serial, nickname in zip(SERIALS, ("FGS-A", "FGS-B"))]
            for future in futures:
                future.result()
        preflight_event("actual_onboarding_permissions_completed")

        service_deadline = time.monotonic() + 35
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(wait_fgs_active, serial, service_deadline) for serial in SERIALS]
            for future in futures:
                future.result()
        preflight_event("foreground_services_active")

        peer_deadline = time.monotonic() + 90
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(wait_ui, serial, "1 phone in range", peer_deadline, True,
                                   "peer_discovery_timeout") for serial in SERIALS]
            for future in futures:
                future.result()
        preflight_event("both_apps_display_real_peer")

        # Establish compatibility first, then really stop both product services. The recording must
        # show a fresh app launch and real rediscovery rather than presenting a pre-connected state.
        stop_deadline = time.monotonic() + 35
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(quit_via_notification, serial, stop_deadline) for serial in SERIALS]
            for future in futures:
                future.result()
        preflight_event("services_stopped_for_recorded_relaunch")

        recorder_deadline = time.monotonic() + 20
        for serial in SERIALS:
            require_host_recorder(serial, recorder_deadline)
        video_start = time.monotonic()
        recorder_start_deadline = video_start + 12
        for serial, path in zip(SERIALS, recordings):
            start_host_recorder(serial, path, recorder_start_deadline)
        last_recorder_start = time.monotonic()

        # Onboarding is a genuine precondition completed immediately before recording. The video
        # begins from stopped services and then shows a fresh launch and real peer rediscovery.
        event("actual_onboarding_permissions_completed", 0.0)
        deadline = video_start + SCENARIO_BUDGET_SECONDS

        for serial in SERIALS:
            deadline_adb(serial, "recorded_relaunch", deadline,
                         "shell", "am", "start", "-n", COMPONENT)
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(wait_activity_resumed, serial, min(deadline, time.monotonic()+20))
                       for serial in SERIALS]
            for future in futures:
                future.result()
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(wait_fgs_active, serial, min(deadline, time.monotonic()+35))
                       for serial in SERIALS]
            for future in futures:
                future.result()
        reconnect_deadline = min(deadline, time.monotonic() + 90)
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(wait_ui, serial, "1 phone in range", reconnect_deadline, True,
                                   "peer_discovery_timeout") for serial in SERIALS]
            for future in futures:
                future.result()
        event("both_apps_display_real_peer")

        for serial in SERIALS:
            room = wait_ui(serial, "Public room", deadline, failure_phase="public_room_open")
            tap(serial, room, "public_room_open", deadline)
        send(SERIALS[0], MESSAGE_A, deadline)
        wait_ui(SERIALS[1], MESSAGE_A, deadline, contains=True, failure_phase="message_receive")
        send(SERIALS[1], MESSAGE_B, deadline)
        wait_ui(SERIALS[0], MESSAGE_B, deadline, contains=True, failure_phase="message_receive")
        event("public_messages_received_in_both_directions")

        deadline_adb(SERIALS[0], "notification_expand", deadline,
                     "shell", "input", "keyevent", "3")
        wait_fgs_active(SERIALS[0], min(deadline, time.monotonic() + 8))
        deadline_adb(SERIALS[0], "notification_expand", deadline,
                     "shell", "cmd", "statusbar", "expand-notifications")
        wait_ui(SERIALS[0], "Mesh running", deadline, contains=True, failure_phase="notification_visible")
        event("background_connected_device_notification_visible")

        send(SERIALS[1], MESSAGE_BACKGROUND, deadline)
        event("public_message_sent_while_other_app_in_background")
        notification = wait_ui(SERIALS[0], "Mesh running", deadline, contains=True,
                               failure_phase="notification_visible")
        tap(SERIALS[0], notification, "notification_open", deadline)
        wait_ui(SERIALS[0], MESSAGE_BACKGROUND, deadline, contains=True, failure_phase="message_receive")
        event("public_message_visible_after_resume_via_fgs_notification")

        quit_via_notification(SERIALS[0], deadline)
        event("quit_action_stopped_service_and_removed_notification")
        if time.monotonic() >= deadline:
            raise GateError("scenario_budget")
        receipt["result"] = SCENARIO_RESULT
    except GateError as error:
        receipt["failureGate"] = error.gate
    except Exception:
        receipt["failureGate"] = gate_name("unclassified_controller_failure")
    finally:
        if receipt["result"] == SCENARIO_RESULT:
            try:
                wait_host_recordings(recordings, last_recorder_start)
            except GateError as error:
                receipt["result"] = "NOT_COMPLETED"
                receipt["failureGate"] = error.gate
        (root / "evidence/attempt.json").write_text(json.dumps(receipt, indent=2)+"\n")

    if receipt["result"] != SCENARIO_RESULT:
        raise RuntimeError("Allowlisted failure gate was written to the receipt")

    export_host_recordings(root, recordings, receipt)


if __name__ == "__main__":
    try:
        main(Path(sys.argv[1]))
    except GateError:
        sys.exit("Real emulator attempt did not complete; see allowlisted receipt gate; no passing video is exported.")
    except Exception:
        sys.exit("Real emulator attempt did not complete; no passing video is exported.")
