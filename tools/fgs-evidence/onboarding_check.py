#!/usr/bin/env python3
"""One-AVD canonical onboarding check with no media or artifact export."""

import json
import os
import math
from pathlib import Path
import sys
from threading import Lock
import time

from capture import (
    GateError,
    ONBOARDING_TRACE_EVENTS,
    SERIALS,
    SERIAL_PHASES,
    boot_and_install,
    deferred_ui_dump_counts,
    onboarding,
    sweep_deferred_ui_dumps,
)


SERIAL = SERIALS[0]
APK_NAME = "wesh108-test-signed.apk"
ONBOARDING_LIMIT_SECONDS = 120
CONTROLLER_LIMIT_SECONDS = 390
MAX_TRACE_EVENTS = 64
TRACE_PREFIX_EVENTS = 8
TRACE_PREFIX = "ONBOARDING_TRACE "
LOCAL_GATES = frozenset({
    "controller_timeout", "ui_cleanup_incomplete_a", "unclassified_controller_failure",
})
ALLOWED_OUTPUT_GATES = LOCAL_GATES | frozenset(
    f"{phase}_a" for phase in SERIAL_PHASES)


def empty_trace_payload():
    return {
        "schemaVersion": 1,
        "events": [],
        "counts": {event: 0 for event in sorted(ONBOARDING_TRACE_EVENTS)},
        "droppedEvents": 0,
    }


class ClosedOnboardingTrace:
    def __init__(self):
        self.payload = empty_trace_payload()
        self.lock = Lock()

    def __call__(self, event, attempt, allocated, elapsed):
        values = (allocated, elapsed)
        if (event not in ONBOARDING_TRACE_EVENTS or type(attempt) is not int
                or not 0 <= attempt <= 2
                or not all(type(value) in (int, float) and math.isfinite(value)
                           and 0 <= value <= CONTROLLER_LIMIT_SECONDS for value in values)):
            raise ValueError("Invalid closed onboarding trace event")
        record = {
            "event": event,
            "attempt": attempt,
            "allocatedSeconds": round(float(allocated), 3),
            "elapsedSeconds": round(float(elapsed), 3),
        }
        with self.lock:
            self.payload["counts"][event] += 1
            if len(self.payload["events"]) < MAX_TRACE_EVENTS:
                self.payload["events"].append(record)
            else:
                self.payload["droppedEvents"] += 1
                self.payload["events"].pop(TRACE_PREFIX_EVENTS)
                self.payload["events"].append(record)


def validated_trace_payload(trace):
    payload = trace.payload
    if set(payload) != {"schemaVersion", "events", "counts", "droppedEvents"}:
        raise ValueError("Invalid closed onboarding trace schema")
    if payload["schemaVersion"] != 1 or set(payload["counts"]) != ONBOARDING_TRACE_EVENTS:
        raise ValueError("Invalid closed onboarding trace enums")
    if (len(payload["events"]) > MAX_TRACE_EVENTS
            or type(payload["droppedEvents"]) is not int or payload["droppedEvents"] < 0
            or any(type(value) is not int or value < 0
                   for value in payload["counts"].values())):
        raise ValueError("Invalid closed onboarding trace bounds")
    if sum(payload["counts"].values()) != len(payload["events"]) + payload["droppedEvents"]:
        raise ValueError("Inconsistent closed onboarding trace counts")
    for record in payload["events"]:
        if (set(record) != {"event", "attempt", "allocatedSeconds", "elapsedSeconds"}
                or record["event"] not in ONBOARDING_TRACE_EVENTS
                or type(record["attempt"]) is not int or not 0 <= record["attempt"] <= 2):
            raise ValueError("Invalid closed onboarding trace record")
        for field in ("allocatedSeconds", "elapsedSeconds"):
            value = record[field]
            if (type(value) not in (int, float) or not math.isfinite(value)
                    or not 0 <= value <= CONTROLLER_LIMIT_SECONDS):
                raise ValueError("Invalid closed onboarding trace timing")
    return payload


def closed_status_gate(gate, passed):
    if gate in ALLOWED_OUTPUT_GATES:
        return gate
    if gate is None and passed:
        return "none"
    return "unclassified_controller_failure"


def main(root: Path) -> int:
    started = time.monotonic()
    deadline = started + CONTROLLER_LIMIT_SECONDS
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
        if not apk.is_file() or apk.is_symlink():
            raise RuntimeError("Missing verified test APK")

        boot_and_install(SERIAL, apk)
        onboarding(
            SERIAL, min(deadline, time.monotonic() + ONBOARDING_LIMIT_SECONDS),
            observer=trace,
        )
        passed = True
    except GateError as error:
        gate = error.gate
    except Exception:
        gate = "unclassified_controller_failure"
    finally:
        try:
            sweep_deferred_ui_dumps(min(deadline, time.monotonic() + 6))
            cleanup_complete = deferred_ui_dump_counts()["a"] == 0
        except Exception:
            cleanup_complete = False
        if not cleanup_complete:
            passed = False
            if gate is None:
                gate = "ui_cleanup_incomplete_a"

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
