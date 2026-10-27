#!/usr/bin/env python3
"""Write a closed two-JSON diagnostic receipt when runner setup fails pre-controller."""

import json
import os
from pathlib import Path
import re
import sys

from capture import CAPTURE_SOURCE
from closed_trace import empty_trace_payload


def main(root: Path) -> int:
    expected = Path(os.environ.get("RUNNER_TEMP", "")) / "fgs-evidence"
    head = os.environ.get("EVIDENCE_HEAD", "")
    evidence = root / "evidence"
    provenance = evidence / "provenance.json"
    attempt = evidence / "attempt.json"
    if (os.environ.get("GITHUB_ACTIONS") != "true"
            or os.environ.get("FGS_DIAGNOSTIC_FAILURE_MEDIA") != "true"
            or root != expected or re.fullmatch(r"[0-9a-f]{40}", head) is None
            or not evidence.is_dir() or evidence.is_symlink()
            or not provenance.is_file() or provenance.is_symlink()):
        return 1
    if attempt.exists():
        return 0 if attempt.is_file() and not attempt.is_symlink() else 1
    receipt = {
        "result": "NOT_COMPLETED",
        "failureGate": "unclassified_controller_failure",
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
        "diagnosticFailureMediaEnabled": True,
        "diagnosticFailureMediaExported": False,
        "recordingsExported": False,
        "finalVideoQualified": False,
    }
    attempt.write_text(json.dumps(receipt, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError
        raise SystemExit(main(Path(sys.argv[1])))
    except SystemExit:
        raise
    except BaseException:
        raise SystemExit(1)
