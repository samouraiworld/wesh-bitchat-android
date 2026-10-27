#!/usr/bin/env python3
"""External UI automation of the unchanged release APK on two synthetic AVDs only."""
from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
from pathlib import Path
import re
import secrets
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
UI_READER_HOST_JAR = "wesh-ui-reader.jar"
UI_READER_CLASS = "org.samouraiworld.wesh.tools.UiHierarchyReader"
UI_READER_SUCCESS = "WESH_UI_READER_OK"
ONBOARDING_TRACE_EVENTS = frozenset({
    "activity_resumed", "welcome_seen", "start_tapped", "bluetooth", "permission",
    "notifications", "auxiliary", "home", "unknown", "ui_prepare", "ui_stale",
    "ui_command", "ui_output", "ui_read", "ui_parse", "ui_complete", "room_open_tapped",
    "send_requested", "confirmation_tapped", "message_bubble", "remote_received",
    "background_requested", "notification_visible", "notification_open_tapped",
    "activity_resumed_after_notification", "quit_requested", "shade_opened",
    "notification_card_found", "expand_tapped", "quit_action_found", "quit_tapped",
    "service_stopped", "notification_absent", "quit_completed",
    "onboarding_completed", "fgs_active", "peers_visible", "messages_bidirectional",
    "background_send", "scenario_started",
})

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
    "ui_reader_prepare", "ui_reader_push", "ui_reader_hash", "ui_reader_root_null",
    "ui_reader_bridge", "ui_reader_api", "ui_reader_dump", "ui_reader_output",
    "ui_reader_disconnect", "ui_reader_cleanup",
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
_UI_DUMP_NONCE = secrets.token_hex(8)
_UI_DUMP_DEFERRED_LOCK = Lock()
_UI_DUMP_DEFERRED = {serial: set() for serial in SERIALS}
_UI_READER_LOCK = Lock()
_UI_READER_PATHS = {}
_UI_READER_READY = set()


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
    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired, E.ParseError):
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
    return (f"/data/local/tmp/wesh-fgs-window-{SIDES[serial]}-{_UI_DUMP_NONCE}-"
            f"{sequence:08d}.xml")


def ui_timeout(deadline, cap):
    """Unlike general adb deadlines, never round a UI budget past its deadline."""
    remaining = deadline - time.monotonic()
    return None if remaining <= 0 else min(cap, remaining)


def emit_onboarding_trace(observer, event, attempt=0, allocated=0.0, elapsed=0.0,
                          serial=None):
    if observer is None:
        return
    values = (allocated, elapsed)
    if (event not in ONBOARDING_TRACE_EVENTS or type(attempt) is not int or attempt < 0
            or not all(isinstance(value, (int, float)) and not isinstance(value, bool)
                       and math.isfinite(value) and value >= 0 for value in values)):
        raise ValueError("Invalid closed onboarding trace event")
    side = "global" if serial is None else SIDES.get(serial)
    if side not in {"a", "b", "global"}:
        raise ValueError("Invalid closed onboarding trace side")
    observer(event, side, attempt, float(allocated), float(elapsed))


def ui_adb(serial, deadline, timeout_phase, error_phase, *args, cap,
           observer=None, trace_event=None, trace_attempt=0):
    timeout = ui_timeout(deadline, cap)
    if timeout is None:
        raise GateError(timeout_phase, serial)
    started = time.monotonic() if observer is not None else None
    try:
        return adb(serial, *args, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise GateError(timeout_phase, serial) from None
    except (RuntimeError, ValueError, OSError):
        raise GateError(error_phase, serial) from None
    finally:
        if observer is not None:
            emit_onboarding_trace(
                observer, trace_event, trace_attempt, timeout,
                max(0.0, time.monotonic() - started),
                serial=serial,
            )


def owned_ui_dump_path(serial, path):
    side = SIDES.get(serial)
    return side is not None and re.fullmatch(
        rf"/data/local/tmp/wesh-fgs-window-{side}-[0-9a-f]{{16}}-\d{{8}}\.xml", path,
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


def ui_reader_guest_path(serial):
    if serial not in SIDES:
        raise ValueError("Only the two owned emulator serials are allowed")
    return f"/data/local/tmp/wesh-fgs-ui-reader-{SIDES[serial]}-{_UI_DUMP_NONCE}.jar"


def owned_ui_reader_path(serial, path):
    side = SIDES.get(serial)
    return side is not None and re.fullmatch(
        rf"/data/local/tmp/wesh-fgs-ui-reader-{side}-[0-9a-f]{{16}}\.jar", path,
    ) is not None


def configure_ui_reader(serial, host_jar, deadline):
    """Push and checksum one tools-only reader for an explicitly selected serial."""
    host_jar = Path(host_jar)
    if (host_jar.name != UI_READER_HOST_JAR or not host_jar.is_file()
            or host_jar.is_symlink()):
        raise GateError("ui_reader_prepare", serial)
    expected = digest(host_jar)
    if re.fullmatch(r"[0-9a-f]{64}", expected) is None:
        raise GateError("ui_reader_hash", serial)
    guest = ui_reader_guest_path(serial)
    with _UI_READER_LOCK:
        existing = _UI_READER_PATHS.get(serial)
        if existing is not None and existing != guest:
            raise GateError("ui_reader_prepare", serial)
        _UI_READER_PATHS[serial] = guest
        _UI_READER_READY.discard(serial)
    try:
        deadline_adb(serial, "ui_reader_prepare", deadline,
                     "shell", "rm", "-f", guest, cap=UI_FILE_COMMAND_SECONDS)
        deadline_adb(serial, "ui_reader_prepare", deadline,
                     "shell", "test", "!", "-e", guest, cap=UI_FILE_COMMAND_SECONDS)
        deadline_adb(serial, "ui_reader_push", deadline,
                     "push", str(host_jar), guest, cap=12)
        deadline_adb(serial, "ui_reader_push", deadline,
                     "shell", "test", "-s", guest, cap=UI_FILE_COMMAND_SECONDS)
        output = deadline_adb(serial, "ui_reader_hash", deadline,
                              "shell", "sha256sum", guest, cap=UI_FILE_COMMAND_SECONDS)
        match = re.fullmatch(r"([0-9a-f]{64})\s+\S+\s*", output)
        if match is None or match.group(1) != expected:
            raise GateError("ui_reader_hash", serial)
    except Exception:
        try:
            timeout = ui_timeout(deadline, UI_POST_CLEANUP_SECONDS)
            if timeout is not None:
                adb(serial, "shell", "rm", "-f", guest, timeout=timeout)
        except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired):
            pass
        raise
    with _UI_READER_LOCK:
        _UI_READER_READY.add(serial)


def cleanup_ui_reader(serial, deadline):
    """Remove only the explicitly configured reader and prove it is absent."""
    with _UI_READER_LOCK:
        guest = _UI_READER_PATHS.get(serial)
    if guest is None:
        return
    if not owned_ui_reader_path(serial, guest):
        raise GateError("ui_reader_cleanup", serial)
    deadline_adb(serial, "ui_reader_cleanup", deadline,
                 "shell", "rm", "-f", guest, cap=UI_POST_CLEANUP_SECONDS)
    deadline_adb(serial, "ui_reader_cleanup", deadline,
                 "shell", "test", "!", "-e", guest, cap=UI_FILE_COMMAND_SECONDS)
    with _UI_READER_LOCK:
        if _UI_READER_PATHS.get(serial) == guest:
            del _UI_READER_PATHS[serial]
        _UI_READER_READY.discard(serial)


def ui_reader_command(serial, path, deadline, observer=None, trace_attempt=0):
    with _UI_READER_LOCK:
        guest = _UI_READER_PATHS.get(serial)
        ready = serial in _UI_READER_READY
    if guest is None or not ready or not owned_ui_reader_path(serial, guest):
        raise GateError("ui_reader_prepare", serial)
    if not owned_ui_dump_path(serial, path):
        raise GateError("ui_reader_prepare", serial)
    timeout = ui_timeout(deadline, UI_DUMP_COMMAND_SECONDS)
    if timeout is None:
        raise GateError("ui_dump_command_timeout", serial)
    started = time.monotonic() if observer is not None else None
    try:
        result = subprocess.run([
            "adb", "-s", serial, "shell", "env",
            f"CLASSPATH={guest}:/system/framework/uiautomator.jar",
            "app_process", "/system/bin", UI_READER_CLASS, path,
        ], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise GateError("ui_dump_command_timeout", serial) from None
    except (OSError, ValueError):
        raise GateError("ui_dump_command_error", serial) from None
    finally:
        if observer is not None:
            emit_onboarding_trace(
                observer, "ui_command", trace_attempt, timeout,
                max(0.0, time.monotonic() - started), serial=serial,
            )
    failure_by_status = {
        20: "ui_reader_api", 21: "ui_reader_bridge", 22: "ui_reader_api",
        23: "ui_reader_root_null", 24: "ui_reader_dump",
        25: "ui_reader_output", 26: "ui_reader_disconnect",
    }
    if result.returncode != 0:
        raise GateError(failure_by_status.get(
            result.returncode, "ui_dump_command_error"), serial)
    lines = [line.strip() for line in result.stdout.decode(
        errors="replace").splitlines() if line.strip()]
    if not lines or lines[-1] != UI_READER_SUCCESS:
        raise GateError("ui_reader_output", serial)


def ui_reader_healthcheck(serial, deadline):
    """Prove the configured one-shot reader returns a parseable nonempty hierarchy."""
    nodes(serial, deadline)


def ui_dump_attempt(serial, path, deadline, observer=None, trace_attempt=0):
    """Create, read and remove one fresh hierarchy without exporting its contents."""
    read_complete = False
    try:
        ui_adb(serial, deadline, "ui_dump_prepare_cleanup", "ui_dump_prepare_cleanup",
               "shell", "rm", "-f", path, cap=UI_FILE_COMMAND_SECONDS,
               observer=observer, trace_event="ui_prepare", trace_attempt=trace_attempt)
        ui_adb(serial, deadline, "ui_dump_stale", "ui_dump_stale",
               "shell", "test", "!", "-e", path, cap=UI_FILE_COMMAND_SECONDS,
               observer=observer, trace_event="ui_stale", trace_attempt=trace_attempt)
        with _UI_READER_LOCK:
            reader_configured = serial in _UI_READER_PATHS
            reader_ready = serial in _UI_READER_READY
        if reader_configured and not reader_ready:
            raise GateError("ui_reader_prepare", serial)
        if reader_ready:
            ui_reader_command(
                serial, path, deadline, observer=observer, trace_attempt=trace_attempt)
        else:
            ui_adb(serial, deadline, "ui_dump_command_timeout", "ui_dump_command_error",
                   "shell", "uiautomator", "dump", path, cap=UI_DUMP_COMMAND_SECONDS,
                   observer=observer, trace_event="ui_command", trace_attempt=trace_attempt)
        ui_adb(serial, deadline, "ui_dump_output_missing", "ui_dump_output_missing",
               "shell", "test", "-s", path, cap=UI_FILE_COMMAND_SECONDS,
               observer=observer, trace_event="ui_output", trace_attempt=trace_attempt)
        xml = ui_adb(serial, deadline, "ui_dump_read", "ui_dump_read",
                     "exec-out", "cat", path, cap=UI_DUMP_COMMAND_SECONDS,
                     observer=observer, trace_event="ui_read", trace_attempt=trace_attempt)
        read_complete = True
        if not xml.strip():
            raise GateError("ui_dump_output_missing", serial)
        parse_started = time.monotonic() if observer is not None else None
        try:
            root = E.fromstring(xml)
        except E.ParseError:
            raise GateError("ui_dump_parse", serial) from None
        finally:
            if observer is not None:
                emit_onboarding_trace(
                    observer, "ui_parse", trace_attempt, 0.0,
                    max(0.0, time.monotonic() - parse_started),
                    serial=serial,
                )
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


def nodes(serial, deadline, observer=None):
    """Take at most two fresh snapshots inside a 26-second/caller deadline."""
    snapshot_started = time.monotonic()
    snapshot_deadline = min(deadline, snapshot_started + UI_SNAPSHOT_BUDGET_SECONDS)
    if snapshot_deadline - time.monotonic() <= 0:
        failure = GateError("ui_dump_command_timeout", serial)
        failure.caller_deadline_reached = time.monotonic() >= deadline
        raise failure
    last_failure = None
    for attempt in range(UI_DUMP_ATTEMPTS):
        if time.monotonic() >= snapshot_deadline:
            break
        path = ui_dump_path(serial)
        try:
            if observer is None:
                items = ui_dump_attempt(serial, path, snapshot_deadline)
            else:
                items = ui_dump_attempt(
                    serial, path, snapshot_deadline, observer=observer,
                    trace_attempt=attempt + 1,
                )
                emit_onboarding_trace(
                    observer, "ui_complete", attempt + 1,
                    max(0.0, snapshot_deadline - snapshot_started),
                    max(0.0, time.monotonic() - snapshot_started),
                    serial=serial,
                )
            return items
        except GateError as failure:
            failure.caller_deadline_reached = time.monotonic() >= deadline
            last_failure = failure
            if attempt + 1 < UI_DUMP_ATTEMPTS:
                bounded_sleep(snapshot_deadline, 0.25)
    if last_failure is not None:
        raise last_failure
    failure = GateError("ui_dump_command_timeout", serial)
    failure.caller_deadline_reached = time.monotonic() >= deadline
    raise failure


def labels(items):
    return "\n".join(n.get("text", "") + "\n" + n.get("content-desc", "") for n in items)


def select(items, label, contains=False):
    def matches(value):
        return label.casefold() in value.casefold() if contains else label.casefold() == value.casefold()
    return next((n for n in items if n.get("enabled") == "true" and
                 any(matches(n.get(key, "")) for key in ("text", "content-desc"))), None)


def action_candidates(items, label, contains=False):
    def matches(node):
        def value_matches(value):
            return (label.casefold() in value.casefold() if contains
                    else label.casefold() == value.casefold())
        return (node.get("enabled") == "true"
                and any(value_matches(node.get(key, ""))
                        for key in ("text", "content-desc")))

    def actionable(node):
        return (node.get("enabled") == "true"
                and (node.get("clickable") == "true"
                     or node.get("class", "").rsplit(".", 1)[-1] == "Button"))

    found = []
    for label_node in (node for node in items if matches(node)):
        candidate = label_node if actionable(label_node) else None
        if candidate is None:
            ancestors = [node for node in items if actionable(node)
                         and any(descendant is label_node for descendant in node.iter("node"))]
            if ancestors:
                candidate = min(ancestors, key=lambda node: len(list(node.iter("node"))))
        if candidate is not None and not any(existing is candidate for existing in found):
            found.append(candidate)
    return found


def select_action(items, label, contains=False):
    """Return one matching actionable node, or its actionable containing ancestor."""
    candidates = action_candidates(items, label, contains)
    return candidates[0] if candidates else None


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


def wait_action(serial, label, deadline, contains=False, failure_phase="ui_action"):
    while time.monotonic() < deadline:
        target = select_action(nodes(serial, deadline), label, contains)
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


def onboarding(serial, deadline, observer=None):
    trace_started = time.monotonic() if observer is not None else None

    def progress(event):
        if observer is not None:
            emit_onboarding_trace(
                observer, event, 0, 0.0,
                max(0.0, time.monotonic() - trace_started),
                serial=serial,
            )

    deadline_adb(serial, "activity_launch", deadline, "shell", "am", "start", "-n", COMPONENT)
    wait_activity_resumed(serial, min(deadline, time.monotonic() + 20))
    progress("activity_resumed")
    last_phase = "onboarding_unknown"
    welcome_seen = False
    start_tapped = False
    while time.monotonic() < deadline:
        try:
            items = (nodes(serial, deadline) if observer is None
                     else nodes(serial, deadline, observer=observer))
        except GateError as failure:
            if (failure.gate == gate_name("ui_dump_command_timeout", serial)
                    and getattr(failure, "caller_deadline_reached", False)):
                break
            raise
        home_visible = select(items, "Public room") is not None
        if home_visible and welcome_seen and start_tapped:
            progress("home")
            return
        nickname_label = select(items, "Your nickname")
        if nickname_label is not None:
            if not welcome_seen:
                welcome_seen = True
                progress("welcome_seen")
            last_phase = "onboarding_welcome_timeout"
            start = select_action(items, "Start")
            if start is not None and not start_tapped:
                tap(serial, start, "onboarding_welcome_start", deadline)
                start_tapped = True
                progress("start_tapped")
            if not bounded_sleep(deadline, 0.4):
                break
            continue

        if home_visible:
            last_phase = ("onboarding_welcome_timeout" if welcome_seen
                          else "onboarding_unknown")
            progress("unknown")
            if not bounded_sleep(deadline, 0.4):
                break
            continue

        actions = (
            ("Allow notifications", "onboarding_notifications", "notifications"),
            ("While using the app", "onboarding_permission", "permission"),
            ("Allow", "onboarding_permission", "permission"),
            ("Continue", "onboarding_bluetooth", "bluetooth"),
            ("Turn on Bluetooth", "onboarding_bluetooth", "bluetooth"),
            ("Skip for Now", "onboarding_auxiliary", "auxiliary"),
        )
        acted = False
        for label, phase, progress_event in actions:
            target = select_action(items, label)
            if target is not None:
                last_phase = phase
                tap(serial, target, phase, deadline)
                progress(progress_event)
                acted = True
                break
        if not acted:
            last_phase = "onboarding_unknown"
            progress("unknown")
        if not bounded_sleep(deadline, 0.4):
            break
    raise GateError(last_phase, serial)


def trace_scenario_progress(observer, event, started, serial=None):
    if observer is None:
        return
    emit_onboarding_trace(
        observer, event, 0, 0.0,
        max(0.0, time.monotonic() - started),
        serial=serial,
    )


def node_is_within(node, ancestor):
    return any(descendant is node for descendant in ancestor.iter("node"))


def is_editor(node):
    return (node.get("class", "").casefold().endswith("edittext")
            or node.get("editable") == "true")


def message_bubble(items, message):
    if select(items, "Send to the public room?") is not None:
        return None
    editors = [node for node in items if is_editor(node)]
    for node in items:
        if (node.get("package") != PACKAGE or is_editor(node)
                or any(node_is_within(node, editor) or node_is_within(editor, node)
                       for editor in editors)):
            continue
        text = node.get("text", "")
        description = node.get("content-desc", "")
        semantic_parts = [part.strip() for part in description.split(", ")]
        if text == message or message in semantic_parts:
            return node
    return None


def confirmation_action(items):
    marker = select(items, "Send to the public room?")
    if marker is None or marker.get("package") != PACKAGE:
        return None
    scopes = []
    for scope in items:
        if not node_is_within(marker, scope):
            continue
        scoped = [node for node in scope.iter("node") if node.get("package") == PACKAGE]
        if any(is_editor(node) for node in scoped):
            continue
        if (select(scoped, "Anyone around can read, relay and keep your message. "
                          "A nickname does not prove an identity.") is None
                or select_action(scoped, "Cancel") is None):
            continue
        scopes.append((len(scoped), scoped))
    if not scopes:
        return None
    minimum = min(size for size, _scoped in scopes)
    minimal_scopes = [scoped for size, scoped in scopes if size == minimum]
    if len(minimal_scopes) != 1:
        return None
    candidates = action_candidates(minimal_scopes[0], "Send in public")
    return candidates[0] if len(candidates) == 1 else None


def wait_message_bubble(serial, message, deadline, observer=None, trace_started=None,
                        remote=False):
    while time.monotonic() < deadline:
        if message_bubble(nodes(serial, deadline), message) is not None:
            trace_scenario_progress(
                observer, "remote_received" if remote else "message_bubble",
                trace_started, serial,
            )
            return
        if not bounded_sleep(deadline, 0.2):
            break
    raise GateError("message_receive" if remote else "message_submit", serial)


def send(serial, message, deadline, observer=None, trace_started=None):
    target = wait_action(serial, "Public message — everyone around", deadline,
                         failure_phase="message_composer")
    tap(serial, target, "message_composer", deadline)
    deadline_adb(serial, "message_composer", deadline, "shell", "input", "text", message)
    submit = wait_action(serial, "Send in public", deadline, failure_phase="message_submit")
    tap(serial, submit, "message_submit", deadline)
    trace_scenario_progress(observer, "send_requested", trace_started, serial)
    confirmed = False
    while time.monotonic() < deadline:
        items = nodes(serial, deadline)
        state = public_send_state(items, message)
        if state == "confirm" and not confirmed:
            confirmation = confirmation_action(items)
            if confirmation is None:
                raise GateError("message_confirmation", serial)
            tap(serial, confirmation, "message_confirmation", deadline)
            confirmed = True
            trace_scenario_progress(observer, "confirmation_tapped", trace_started, serial)
        elif state == "submitted":
            trace_scenario_progress(observer, "message_bubble", trace_started, serial)
            return
        if not bounded_sleep(deadline, 0.2):
            break
    raise GateError("message_submit", serial)


def public_send_state(items, message):
    if select(items, "Send to the public room?") is not None:
        return "confirm"
    if message_bubble(items, message) is not None:
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


SYSTEM_UI_PACKAGE = "com.android.systemui"
NOTIFICATION_STACK_RESOURCE_ID = f"{SYSTEM_UI_PACKAGE}:id/notification_stack_scroller"
NOTIFICATION_ROW_RESOURCE_ID = f"{SYSTEM_UI_PACKAGE}:id/expandableNotificationRow"
NOTIFICATION_EXPAND_RESOURCE_ID = "android:id/expand_button"
NOTIFICATION_EXPAND_CLASS = "android.widget.Button"


def notification_stack(items):
    candidates = [node for node in items
                  if node.get("package") == SYSTEM_UI_PACKAGE
                  and node.get("resource-id") == NOTIFICATION_STACK_RESOURCE_ID]
    return candidates[0] if len(candidates) == 1 else None


def notification_content_nodes(stack, content):
    folded = content.casefold()
    return [node for node in stack.iter("node")
            if node.get("package") == SYSTEM_UI_PACKAGE
            and any(folded in node.get(key, "").casefold()
                    for key in ("text", "content-desc"))]


def notification_row(items, content):
    stack = notification_stack(items)
    if stack is None:
        return None
    matches = []
    for row in stack.iter("node"):
        if (row.get("package") != SYSTEM_UI_PACKAGE
                or row.get("resource-id") != NOTIFICATION_ROW_RESOURCE_ID):
            continue
        if notification_content_nodes(row, content):
            matches.append(row)
    return matches[0] if len(matches) == 1 else None


def scoped_notification_action(items, content, action, resource_id=None):
    """Find one action in the exact SystemUI row that contains the notification text."""
    row = notification_row(items, content)
    if row is None:
        return None
    scoped = [node for node in row.iter("node")
              if node.get("package") == SYSTEM_UI_PACKAGE]
    actions = action_candidates(scoped, action)
    if resource_id is not None:
        actions = [node for node in actions
                   if node.get("resource-id") == resource_id
                   and (resource_id != NOTIFICATION_EXPAND_RESOURCE_ID
                        or node.get("class") == NOTIFICATION_EXPAND_CLASS)]
    return (row, actions[0]) if len(actions) == 1 else None


def wait_scoped_notification_action(serial, content, action, deadline, failure_phase,
                                    resource_id=None):
    while time.monotonic() < deadline:
        match = scoped_notification_action(
            nodes(serial, deadline), content, action, resource_id=resource_id)
        if match is not None:
            return match
        if not bounded_sleep(deadline, 0.4):
            break
    raise GateError(failure_phase, serial)


def quit_via_notification(serial, deadline, observer=None, trace_started=None):
    def progress(event):
        if observer is not None:
            if trace_started is None:
                raise ValueError("Trace start is required with an observer")
            trace_scenario_progress(observer, event, trace_started, serial)

    progress("quit_requested")
    deadline_adb(serial, "notification_expand", deadline, "shell", "input", "keyevent", "3")
    timeout = bounded_timeout(deadline, 8)
    if timeout is None or not fgs_active(serial, timeout):
        raise GateError("fgs_timeout", serial)
    deadline_adb(serial, "notification_expand", deadline,
                 "shell", "cmd", "statusbar", "expand-notifications")
    items = nodes(serial, deadline)
    if notification_stack(items) is None:
        raise GateError("notification_expand", serial)
    progress("shade_opened")
    card = notification_row(items, "Mesh running")
    if card is None:
        raise GateError("notification_expand", serial)
    progress("notification_card_found")
    scoped_quit = scoped_notification_action(items, "Mesh running", "Quit Wesh")
    if scoped_quit is None:
        collapsed = scoped_notification_action(
            items, "Mesh running", "Expand", resource_id=NOTIFICATION_EXPAND_RESOURCE_ID)
        if collapsed is None:
            raise GateError("notification_expand", serial)
        _card, expand = collapsed
        tap(serial, expand, "notification_expand", deadline)
        progress("expand_tapped")
        _card, quit_action = wait_scoped_notification_action(
            serial, "Mesh running", "Quit Wesh", deadline, "quit_action")
    else:
        _card, quit_action = scoped_quit
    progress("quit_action_found")
    tap(serial, quit_action, "quit_action", deadline)
    progress("quit_tapped")
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
    progress("service_stopped")
    deadline_adb(serial, "notification_expand", deadline,
                 "shell", "cmd", "statusbar", "expand-notifications")
    final_items = nodes(serial, deadline)
    final_stack = notification_stack(final_items)
    if final_stack is None:
        raise GateError("notification_expand", serial)
    progress("shade_opened")
    if notification_content_nodes(final_stack, "Mesh running"):
        raise GateError("quit_timeout", serial)
    progress("notification_absent")


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


def run_product_scenario(deadline, event, observer=None):
    """Run the real product flow shared by media and non-media controllers."""
    trace_started = time.monotonic()
    onboarding_deadline = min(deadline, time.monotonic() + 90)
    with ThreadPoolExecutor(2) as pool:
        futures = [pool.submit(onboarding, serial, onboarding_deadline, observer)
                   for serial in SERIALS]
        for future in futures:
            future.result()
    event("actual_onboarding_permissions_completed")
    trace_scenario_progress(observer, "onboarding_completed", trace_started)

    service_deadline = min(deadline, time.monotonic() + 35)
    with ThreadPoolExecutor(2) as pool:
        futures = [pool.submit(wait_fgs_active, serial, service_deadline) for serial in SERIALS]
        for future in futures:
            future.result()
    event("foreground_services_active")
    trace_scenario_progress(observer, "fgs_active", trace_started)

    peer_deadline = min(deadline, time.monotonic() + 90)
    with ThreadPoolExecutor(2) as pool:
        futures = [pool.submit(wait_ui, serial, "1 phone in range", peer_deadline, True,
                               "peer_discovery_timeout") for serial in SERIALS]
        for future in futures:
            future.result()
    event("both_apps_display_real_peer")
    trace_scenario_progress(observer, "peers_visible", trace_started)

    for serial in SERIALS:
        room = wait_action(serial, "Public room", deadline, failure_phase="public_room_open")
        tap(serial, room, "public_room_open", deadline)
        trace_scenario_progress(observer, "room_open_tapped", trace_started, serial)
    send(SERIALS[0], MESSAGE_A, deadline, observer, trace_started)
    wait_activity_resumed(SERIALS[1], min(deadline, time.monotonic() + 8))
    wait_message_bubble(
        SERIALS[1], MESSAGE_A, deadline, observer, trace_started, remote=True)
    send(SERIALS[1], MESSAGE_B, deadline, observer, trace_started)
    wait_activity_resumed(SERIALS[0], min(deadline, time.monotonic() + 8))
    wait_message_bubble(
        SERIALS[0], MESSAGE_B, deadline, observer, trace_started, remote=True)
    event("public_messages_received_in_both_directions")
    trace_scenario_progress(observer, "messages_bidirectional", trace_started)

    deadline_adb(SERIALS[0], "notification_expand", deadline,
                 "shell", "input", "keyevent", "3")
    trace_scenario_progress(observer, "background_requested", trace_started, SERIALS[0])
    wait_fgs_active(SERIALS[0], min(deadline, time.monotonic() + 8))
    deadline_adb(SERIALS[0], "notification_expand", deadline,
                 "shell", "cmd", "statusbar", "expand-notifications")
    wait_ui(SERIALS[0], "Mesh running", deadline, contains=True,
            failure_phase="notification_visible")
    trace_scenario_progress(observer, "notification_visible", trace_started, SERIALS[0])
    event("background_connected_device_notification_visible")

    send(SERIALS[1], MESSAGE_BACKGROUND, deadline, observer, trace_started)
    event("public_message_sent_while_other_app_in_background")
    trace_scenario_progress(observer, "background_send", trace_started, SERIALS[1])
    notification = wait_action(SERIALS[0], "Mesh running", deadline, contains=True,
                               failure_phase="notification_visible")
    tap(SERIALS[0], notification, "notification_open", deadline)
    trace_scenario_progress(observer, "notification_open_tapped", trace_started, SERIALS[0])
    wait_activity_resumed(SERIALS[0], min(deadline, time.monotonic() + 8))
    trace_scenario_progress(
        observer, "activity_resumed_after_notification", trace_started, SERIALS[0])
    wait_message_bubble(
        SERIALS[0], MESSAGE_BACKGROUND, deadline, observer, trace_started, remote=True)
    event("public_message_visible_after_resume_via_fgs_notification")

    quit_via_notification(SERIALS[0], deadline, observer, trace_started)
    trace_scenario_progress(observer, "quit_completed", trace_started, SERIALS[0])
    event("quit_action_stopped_service_and_removed_notification")
    if time.monotonic() >= deadline:
        raise GateError("scenario_budget")


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
        run_product_scenario(deadline, event)
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
