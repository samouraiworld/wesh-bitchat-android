#!/usr/bin/env python3
"""Write a closed two-JSON receipt when runner setup fails pre-controller."""

import json
import os
from pathlib import Path
import re
import sys

from capture import CAPTURE_SOURCE, RUNNER_PREREQUISITE_GATES
from closed_trace import empty_trace_payload


def main(root: Path, gate: str) -> int:
    expected = Path(os.environ.get("RUNNER_TEMP", "")) / "fgs-evidence"
    head = os.environ.get("EVIDENCE_HEAD", "")
    evidence = root / "evidence"
    provenance = evidence / "provenance.json"
    attempt = evidence / "attempt.json"
    diagnostic_value = os.environ.get("FGS_DIAGNOSTIC_FAILURE_MEDIA", "false")
    if (os.environ.get("GITHUB_ACTIONS") != "true"
            or diagnostic_value not in {"false", "true"}
            or root != expected or re.fullmatch(r"[0-9a-f]{40}", head) is None
            or gate not in RUNNER_PREREQUISITE_GATES
            or not evidence.is_dir() or evidence.is_symlink()
            or not provenance.is_file() or provenance.is_symlink()):
        return 1
    if attempt.exists():
        return (0 if attempt.is_file() and not attempt.is_symlink()
                and {path.name for path in evidence.iterdir()}
                == {"attempt.json", "provenance.json"} else 1)
    if {path.name for path in evidence.iterdir()} != {"provenance.json"}:
        return 1
    receipt = {
        "result": "NOT_COMPLETED",
        "failureGate": gate,
        "preflightEvents": [],
        "events": [],
        "environment": "Two API35 x86_64 AVDs; netsim simulated BLE",
        "emulator": "37.2.12",
        "systemImageRevision": 9,
        "evidenceSource": head,
        "captureSource": CAPTURE_SOURCE,
        "notPhysicalEvidence": True,
        "notPlaySigned": True,
        "failureElapsedSeconds": 0.0,
        "deferredUiDumpCleanupCountBySide": {"a": 0, "b": 0},
        "closedUiTrace": empty_trace_payload(),
        "diagnosticFailureMediaEnabled": diagnostic_value == "true",
        "diagnosticFailureMediaExported": False,
        "recordingsExported": False,
        "finalVideoQualified": False,
    }
    with attempt.open("x") as output:
        output.write(json.dumps(receipt, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    try:
        if len(sys.argv) != 3:
            raise ValueError
        raise SystemExit(main(Path(sys.argv[1]), sys.argv[2]))
    except SystemExit:
        raise
    except BaseException:
        raise SystemExit(1)
