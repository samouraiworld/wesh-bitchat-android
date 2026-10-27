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
MESSAGE_A = "FGS-public-A-108"
MESSAGE_B = "FGS-public-B-108"
MESSAGE_BACKGROUND = "FGS-background-108"
SCENARIO_RESULT = "SCENARIO_PASS_RECORDINGS_AWAIT_LOCAL_VALIDATION"
CAPTURE_SOURCE = "ANDROID_EMULATOR_HOST_PHYSICAL_DISPLAY_WEBM"
RECORDING_LIMIT_SECONDS = 180
SCENARIO_BUDGET_SECONDS = 165
UI_SNAPSHOT_BUDGET_SECONDS = 26
UI_DUMP_COMMAND_SECONDS = 13
UI_DUMP_ATTEMPTS = 2
UI_POST_CLEANUP_SECONDS = 3
UI_FILE_COMMAND_SECONDS = 2

SERIAL_PHASES = frozenset({
    "boot_timeout", "emulator_identity", "api_image", "device_setup",
    "bluetooth_on_timeout", "install", "package_verify", "activity_launch",
    "activity_resume_timeout", "ui_dump_command_timeout", "ui_dump_command_error",
    "ui_dump_stale", "ui_dump_output_missing", "ui_dump_read", "ui_dump_parse",
    "ui_dump_prepare_cleanup", "ui_action",
    "onboarding_welcome_start", "onboarding_welcome_timeout", "onboarding_bluetooth",
    "onboarding_notifications", "onboarding_permission", "onboarding_auxiliary",
    "onboarding_unknown", "fgs_timeout", "peer_discovery_timeout",
    "public_room_open", "message_composer", "message_submit",
    "message_confirmation", "message_receive", "notification_expand",
    "notification_visible", "notification_open", "quit_action", "quit_timeout",
    "host_recorder_help_transport", "host_recorder_help_contract", "host_recorder_start",
    "host_recording_file",
})
GLOBAL_GATES = frozenset({
    "unclassified_controller_failure", "scenario_budget", "scenario_clock", "evidence_export",
})
PREFLIGHT_STEPS = ("boot_install_bluetooth_ready",)
SCENARIO_STEPS = (
    "recording_commands_started", "actual_onboarding_permissions_completed",
    "foreground_services_active", "both_apps_display_real_peer",
    "public_messages_received_in_both_directions", "background_connected_device_notification_visible",
    "public_message_sent_while_other_app_in_background", "public_message_visible_after_resume_via_fgs_notification",
    "quit_action_stopped_service_and_removed_notification",
)

_UI_DUMP_SEQUENCE_LOCK = Lock()
_UI_DUMP_SEQUENCE = {serial: 0 for serial in SERIALS}
_UI_DUMP_DEFERRED_LOCK = Lock()
_UI_DUMP_DEFERRED = {serial: set() for serial in SERIALS}


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


def owned_ui_dump_path(serial, path):
    side = SIDES.get(serial)
    return side is not None and re.fullmatch(
        rf"/data/local/tmp/wesh-fgs-window-{side}-\d{{8}}\.xml", path,
    ) is not None


def defer_ui_dump_cleanup(serial, path):
    if not owned_ui_dump_path(serial, path):
        return False
    with _UI_DUMP_DEFERRED_LOCK:
        _UI_DUMP_DEFERRED[serial].add(path)
    return True


def best_effort_ui_dump_cleanup(serial, path, deadline):
    """Attempt bounded removal; retain only an owned path in memory on failure."""
    timeout = ui_timeout(deadline, UI_POST_CLEANUP_SECONDS)
    if timeout is None:
        defer_ui_dump_cleanup(serial, path)
        return False
    try:
        adb(serial, "shell", "rm", "-f", path, timeout=timeout)
    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired):
        defer_ui_dump_cleanup(serial, path)
        return False
    with _UI_DUMP_DEFERRED_LOCK:
        _UI_DUMP_DEFERRED[serial].discard(path)
    return True


def sweep_deferred_ui_dumps(deadline):
    """Retry controller-owned paths only; never turn hygiene into scenario success/failure."""
    for serial in SERIALS:
        with _UI_DUMP_DEFERRED_LOCK:
            paths = sorted(_UI_DUMP_DEFERRED[serial])
        if not paths or not all(owned_ui_dump_path(serial, path) for path in paths):
            continue
        timeout = ui_timeout(deadline, UI_POST_CLEANUP_SECONDS)
        if timeout is None:
            continue
        try:
            adb(serial, "shell", "rm", "-f", *paths, timeout=timeout)
        except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired):
            continue
        with _UI_DUMP_DEFERRED_LOCK:
            _UI_DUMP_DEFERRED[serial].difference_update(paths)


def deferred_ui_dump_counts():
    with _UI_DUMP_DEFERRED_LOCK:
        return {SIDES[serial]: len(_UI_DUMP_DEFERRED[serial]) for serial in SERIALS}


def append_scenario_event(receipt, name, video_start, elapsed=None):
    index = len(receipt["events"])
    if index >= len(SCENARIO_STEPS) or name != SCENARIO_STEPS[index]:
        raise ValueError("Unallowlisted scenario event sequence")
    if name == "recording_commands_started":
        if elapsed != 0.0:
            raise ValueError("Recording clock must start at zero")
        value = 0.0
    else:
        if elapsed is not None:
            raise ValueError("Recorded product events require the real clock")
        value = time.monotonic() - video_start
        if value <= 0 or value >= SCENARIO_BUDGET_SECONDS:
            raise GateError("scenario_budget")
    rounded = round(value, 3)
    if name != "recording_commands_started" and (
            rounded <= 0 or rounded >= SCENARIO_BUDGET_SECONDS):
        raise GateError("scenario_budget")
    if receipt["events"] and rounded <= receipt["events"][-1]["elapsedSeconds"]:
        raise GateError("scenario_clock")
    receipt["events"].append({"step": name, "elapsedSeconds": rounded})


def ui_dump_attempt(serial, path, deadline):
    """Create, read and remove one fresh hierarchy without exporting its contents."""
    read_complete = False
    try:
        ui_adb(serial, deadline, "ui_dump_prepare_cleanup", "ui_dump_prepare_cleanup",
               "shell", "rm", "-f", path, cap=UI_FILE_COMMAND_SECONDS)
        ui_adb(serial, deadline, "ui_dump_stale", "ui_dump_stale",
               "shell", "test", "!", "-e", path, cap=UI_FILE_COMMAND_SECONDS)
        ui_adb(serial, deadline, "ui_dump_command_timeout", "ui_dump_command_error",
               "shell", "uiautomator", "dump", path, cap=UI_DUMP_COMMAND_SECONDS)
        ui_adb(serial, deadline, "ui_dump_output_missing", "ui_dump_output_missing",
               "shell", "test", "-s", path, cap=UI_FILE_COMMAND_SECONDS)
        xml = ui_adb(serial, deadline, "ui_dump_read", "ui_dump_read",
                     "exec-out", "cat", path, cap=UI_DUMP_COMMAND_SECONDS)
        read_complete = True
        if not xml.strip():
            raise GateError("ui_dump_output_missing", serial)
        try:
            root = E.fromstring(xml)
        except E.ParseError:
            raise GateError("ui_dump_parse", serial) from None
        items = list(root.iter("node"))
        if root.tag != "hierarchy" or not items:
            raise GateError("ui_dump_parse", serial)
    except Exception:
        if read_complete:
            best_effort_ui_dump_cleanup(serial, path, deadline)
        else:
            defer_ui_dump_cleanup(serial, path)
        raise
    else:
        best_effort_ui_dump_cleanup(serial, path, deadline)
        return items


def nodes(serial, deadline):
    """Take at most two fresh snapshots inside a 26-second/caller deadline."""
    snapshot_deadline = min(deadline, time.monotonic() + UI_SNAPSHOT_BUDGET_SECONDS)
    if snapshot_deadline - time.monotonic() <= 0:
        raise GateError("ui_dump_command_timeout", serial)
    last_failure = None
    for attempt in range(UI_DUMP_ATTEMPTS):
        if time.monotonic() >= snapshot_deadline:
            break
        path = ui_dump_path(serial)
        try:
            return ui_dump_attempt(serial, path, snapshot_deadline)
        except GateError as failure:
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


def onboarding(serial, deadline):
    deadline_adb(serial, "activity_launch", deadline, "shell", "am", "start", "-n", COMPONENT)
    wait_activity_resumed(serial, min(deadline, time.monotonic() + 20))
    last_phase = "onboarding_unknown"
    while time.monotonic() < deadline:
        items = nodes(serial, deadline)
        if select(items, "Public room") is not None:
            return
        nickname_label = select(items, "Your nickname")
        if nickname_label is not None:
            last_phase = "onboarding_welcome_timeout"
            start = select(items, "Start")
            if start is not None:
                tap(serial, start, "onboarding_welcome_start", deadline)
            if not bounded_sleep(deadline, 0.4):
                break
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
    help_text = deadline_adb(serial, "host_recorder_help_transport", deadline,
                             "emu", "help-verbose")
    if (not console_response_ok(help_text) or
            re.search(r"(?im)^\s*screenrecord(?:\s|$)", help_text) is None):
        raise GateError("host_recorder_help_contract", serial)


def console_response_ok(response):
    lines = [line.strip() for line in response.splitlines() if line.strip()]
    return (bool(lines) and lines[-1].casefold() == "ok" and
            not any(re.match(r"(?i)^ko(?:\s|:|$)", line) for line in lines))


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
    if not console_response_ok(response):
        raise GateError("host_recorder_start", serial)


def wait_host_recordings(recordings, last_recorder_start):
    remaining = last_recorder_start + RECORDING_LIMIT_SECONDS + 10 - time.monotonic()
    if remaining > 0:
        time.sleep(remaining)
    for serial, path in zip(SERIALS, recordings):
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            raise GateError("host_recording_file", serial)


def export_host_recordings(root, recordings, receipt, failure_elapsed_seconds=None):
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
            receipt["failureElapsedSeconds"] = failure_elapsed_seconds
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
        "failureElapsedSeconds": None,
        "deferredUiDumpCleanupCountBySide": {"a": 0, "b": 0},
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
        append_scenario_event(receipt, name, video_start, elapsed)

    try:
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(boot_and_install, serial, root / "wesh108-test-signed.apk") for serial in SERIALS]
            for future in futures:
                future.result()
        preflight_event("boot_install_bluetooth_ready")

        recorder_deadline = time.monotonic() + 20
        for serial in SERIALS:
            require_host_recorder(serial, recorder_deadline)
        video_start = time.monotonic()
        deadline = video_start + SCENARIO_BUDGET_SECONDS
        recorder_start_deadline = min(deadline, video_start + 12)
        for serial, path in zip(SERIALS, recordings):
            start_host_recorder(serial, path, recorder_start_deadline)
        last_recorder_start = time.monotonic()
        event("recording_commands_started", 0.0)

        # The first product launch, onboarding, permission prompts, service start and peer setup are
        # all real actions inside the two host recordings, before the declared device interaction.
        onboarding_deadline = min(deadline, time.monotonic() + 90)
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(onboarding, serial, onboarding_deadline) for serial in SERIALS]
            for future in futures:
                future.result()
        event("actual_onboarding_permissions_completed")

        service_deadline = min(deadline, time.monotonic() + 35)
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(wait_fgs_active, serial, service_deadline) for serial in SERIALS]
            for future in futures:
                future.result()
        event("foreground_services_active")

        peer_deadline = min(deadline, time.monotonic() + 90)
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(wait_ui, serial, "1 phone in range", peer_deadline, True,
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
        receipt["failureElapsedSeconds"] = round(time.monotonic()-attempt_start, 1)
    except Exception:
        receipt["failureGate"] = gate_name("unclassified_controller_failure")
        receipt["failureElapsedSeconds"] = round(time.monotonic()-attempt_start, 1)
    finally:
        if receipt["result"] == SCENARIO_RESULT:
            try:
                wait_host_recordings(recordings, last_recorder_start)
            except GateError as error:
                receipt["result"] = "NOT_COMPLETED"
                receipt["failureGate"] = error.gate
                receipt["failureElapsedSeconds"] = round(time.monotonic()-attempt_start, 1)
        sweep_deferred_ui_dumps(time.monotonic() + 2 * UI_POST_CLEANUP_SECONDS)
        receipt["deferredUiDumpCleanupCountBySide"] = deferred_ui_dump_counts()
        (root / "evidence/attempt.json").write_text(json.dumps(receipt, indent=2)+"\n")

    if receipt["result"] != SCENARIO_RESULT:
        raise RuntimeError("Allowlisted failure gate was written to the receipt")

    export_host_recordings(
        root, recordings, receipt,
        failure_elapsed_seconds=round(time.monotonic()-attempt_start, 1),
    )


if __name__ == "__main__":
    try:
        main(Path(sys.argv[1]))
    except GateError:
        sys.exit("Real emulator attempt did not complete; see allowlisted receipt gate; no passing video is exported.")
    except Exception:
        sys.exit("Real emulator attempt did not complete; no passing video is exported.")
