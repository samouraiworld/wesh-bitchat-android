#!/usr/bin/env python3
"""Two-AVD canonical product scenario check with no media or artifact export."""

from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
from pathlib import Path
import sys
import time

from capture import (
    GLOBAL_GATES,
    GateError,
    SCENARIO_BUDGET_SECONDS,
    SCENARIO_STEPS,
    SERIALS,
    SERIAL_PHASES,
    boot_and_install,
    cleanup_ui_reader,
    configure_ui_reader,
    deferred_ui_dump_counts,
    run_product_scenario,
    sweep_deferred_ui_dumps,
    ui_reader_healthcheck,
)
from onboarding_check import (
    ClosedOnboardingTrace,
    empty_trace_payload,
    validated_trace_payload,
)


APK_NAME = "wesh108-test-signed.apk"
UI_READER_NAME = "wesh-ui-reader.jar"
CONTROLLER_LIMIT_SECONDS = 650
TRACE_PREFIX = "SCENARIO_TRACE "
PRODUCT_STEPS = SCENARIO_STEPS[1:]
LOCAL_GATES = frozenset({
    "controller_timeout", "ui_cleanup_incomplete", "unclassified_controller_failure",
})
ALLOWED_OUTPUT_GATES = (LOCAL_GATES | GLOBAL_GATES | frozenset(
    f"{phase}_{side}" for phase in SERIAL_PHASES for side in ("a", "b")))


def closed_status_gate(gate, passed):
    if gate in ALLOWED_OUTPUT_GATES:
        return gate
    if gate is None and passed:
        return "none"
    return "unclassified_controller_failure"


def main(root: Path) -> int:
    started = time.monotonic()
    controller_deadline = started + CONTROLLER_LIMIT_SECONDS
    gate = None
    passed = False
    trace = ClosedOnboardingTrace()
    try:
        if os.environ.get("GITHUB_ACTIONS") != "true":
            raise RuntimeError("Disposable GitHub runner only")
        head = os.environ.get("EVIDENCE_HEAD", "")
        if len(head) != 40 or any(character not in "0123456789abcdef" for character in head):
            raise RuntimeError("Invalid reviewed head")
        apk = root / APK_NAME
        reader = root / UI_READER_NAME
        if (not apk.is_file() or apk.is_symlink()
                or not reader.is_file() or reader.is_symlink()):
            raise RuntimeError("Missing verified test APK")

        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(boot_and_install, serial, apk) for serial in SERIALS]
            for future in futures:
                future.result()

        reader_deadline = min(controller_deadline, time.monotonic() + 60)
        for serial in SERIALS:
            configure_ui_reader(serial, reader, reader_deadline)
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(ui_reader_healthcheck, serial, reader_deadline)
                       for serial in SERIALS]
            for future in futures:
                future.result()

        scenario_started = time.monotonic()
        scenario_deadline = min(
            controller_deadline, scenario_started + SCENARIO_BUDGET_SECONDS)
        events = [{"step": "scenario_started", "elapsedSeconds": 0.0}]
        trace("scenario_started", "global", 0, 0.0, 0.0)

        def event(name):
            index = len(events) - 1
            if index >= len(PRODUCT_STEPS) or name != PRODUCT_STEPS[index]:
                raise GateError("scenario_clock")
            elapsed = round(time.monotonic() - scenario_started, 3)
            if (not math.isfinite(elapsed) or elapsed <= events[-1]["elapsedSeconds"]
                    or elapsed >= SCENARIO_BUDGET_SECONDS):
                raise GateError("scenario_clock")
            events.append({"step": name, "elapsedSeconds": elapsed})

        run_product_scenario(scenario_deadline, event, observer=trace)
        if [entry["step"] for entry in events[1:]] != list(PRODUCT_STEPS):
            raise GateError("scenario_clock")
        passed = True
    except GateError as error:
        gate = error.gate
    except Exception:
        gate = "unclassified_controller_failure"
    finally:
        reader_cleanup_complete = True
        for serial in SERIALS:
            try:
                cleanup_ui_reader(serial, min(controller_deadline, time.monotonic() + 5))
            except Exception:
                reader_cleanup_complete = False
        try:
            sweep_deferred_ui_dumps(min(controller_deadline, time.monotonic() + 6))
            cleanup_complete = (reader_cleanup_complete
                                and deferred_ui_dump_counts() == {"a": 0, "b": 0})
        except Exception:
            cleanup_complete = False
        if not cleanup_complete:
            passed = False
            if gate is None:
                gate = "ui_cleanup_incomplete"

    elapsed = round(time.monotonic() - started, 1)
    if not math.isfinite(elapsed) or elapsed < 0 or elapsed > CONTROLLER_LIMIT_SECONDS:
        elapsed = 0.0
        passed = False
        gate = "controller_timeout"
    safe_gate = closed_status_gate(gate, passed)
    if safe_gate == "unclassified_controller_failure" and gate not in ALLOWED_OUTPUT_GATES:
        passed = False
    try:
        trace_payload = validated_trace_payload(trace)
    except ValueError:
        passed = False
        safe_gate = "unclassified_controller_failure"
        trace_payload = empty_trace_payload()
    print(f"{'PASS' if passed else 'FAIL'} gate={safe_gate} elapsedSeconds={elapsed:.1f}")
    print(TRACE_PREFIX + json.dumps(trace_payload, sort_keys=True, separators=(",", ":")))
    return 0 if passed else 1


if __name__ == "__main__":
    try:
        path = Path(sys.argv[1]) if len(sys.argv) == 2 else None
        if path is None:
            raise ValueError("owned root required")
        raise SystemExit(main(path))
    except SystemExit:
        raise
    except Exception:
        print("FAIL gate=unclassified_controller_failure elapsedSeconds=0.0")
        print(TRACE_PREFIX + json.dumps(
            empty_trace_payload(), sort_keys=True, separators=(",", ":")))
        raise SystemExit(1)
