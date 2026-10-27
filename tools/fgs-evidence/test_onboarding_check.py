import contextlib
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "onboarding_check", HERE / "onboarding_check.py")
onboarding_check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(onboarding_check)


class OnboardingCheckTests(unittest.TestCase):
    def owned_root(self, directory):
        root = Path(directory)
        (root / onboarding_check.APK_NAME).write_bytes(b"synthetic canonical fixture")
        return root

    def output_lines(self, output):
        lines = output.getvalue().splitlines()
        self.assertEqual(len(lines), 2)
        self.assertTrue(lines[1].startswith(onboarding_check.TRACE_PREFIX))
        trace = json.loads(lines[1][len(onboarding_check.TRACE_PREFIX):])
        return lines[0], trace

    def test_pass_uses_one_avd_real_onboarding_and_one_closed_status_line(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.owned_root(directory)
            output = io.StringIO()
            def observed_onboarding(_serial, _deadline, observer):
                observer("activity_resumed", "a", 0, 0.0, 0.5)
                observer("ui_command", "a", 1, 13.0, 10.2)
                observer("home", "a", 0, 0.0, 11.0)

            with mock.patch.dict(os.environ, {
                    "GITHUB_ACTIONS": "true", "EVIDENCE_HEAD": "a" * 40}, clear=True), \
                    mock.patch.object(onboarding_check, "boot_and_install") as boot, \
                    mock.patch.object(onboarding_check, "onboarding",
                                      side_effect=observed_onboarding) as onboarding, \
                    mock.patch.object(onboarding_check, "sweep_deferred_ui_dumps") as sweep, \
                    mock.patch.object(onboarding_check, "deferred_ui_dump_counts",
                                      return_value={"a": 0, "b": 0}), \
                    mock.patch.object(onboarding_check.time, "monotonic",
                                      side_effect=[100.0, 101.0, 102.0, 103.0]), \
                    contextlib.redirect_stdout(output):
                status = onboarding_check.main(root)
        self.assertEqual(status, 0)
        boot.assert_called_once_with(onboarding_check.SERIAL, root / onboarding_check.APK_NAME)
        onboarding.assert_called_once()
        self.assertEqual(onboarding.call_args.args[0], "emulator-5554")
        self.assertIsInstance(onboarding.call_args.kwargs["observer"],
                              onboarding_check.ClosedOnboardingTrace)
        sweep.assert_called_once()
        status_line, trace = self.output_lines(output)
        self.assertEqual(status_line, "PASS gate=none elapsedSeconds=3.0")
        self.assertEqual(trace["counts"]["activity_resumed"], 1)
        self.assertEqual(trace["counts"]["ui_command"], 1)
        self.assertEqual(trace["counts"]["home"], 1)
        self.assertEqual(trace["droppedEvents"], 0)
        self.assertEqual(trace["schemaVersion"], 2)
        self.assertTrue(all(event["side"] == "a" for event in trace["events"]))

    def test_allowlisted_failure_and_cleanup_failure_remain_redacted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.owned_root(directory)
            error = onboarding_check.GateError("onboarding_welcome_timeout", "emulator-5554")
            output = io.StringIO()
            with mock.patch.dict(os.environ, {
                    "GITHUB_ACTIONS": "true", "EVIDENCE_HEAD": "b" * 40}, clear=True), \
                    mock.patch.object(onboarding_check, "boot_and_install", side_effect=error), \
                    mock.patch.object(onboarding_check, "sweep_deferred_ui_dumps"), \
                    mock.patch.object(onboarding_check, "deferred_ui_dump_counts",
                                      return_value={"a": 0, "b": 0}), \
                    mock.patch.object(onboarding_check.time, "monotonic",
                                      side_effect=[10.0, 11.0, 12.0]), \
                    contextlib.redirect_stdout(output):
                status = onboarding_check.main(root)
        self.assertEqual(status, 1)
        status_line, trace = self.output_lines(output)
        self.assertEqual(
            status_line, "FAIL gate=onboarding_welcome_timeout_a elapsedSeconds=2.0")
        self.assertEqual(trace, onboarding_check.empty_trace_payload())

        with tempfile.TemporaryDirectory() as directory:
            root = self.owned_root(directory)
            output = io.StringIO()
            error = onboarding_check.GateError(
                "onboarding_welcome_timeout", "emulator-5554")
            with mock.patch.dict(os.environ, {
                    "GITHUB_ACTIONS": "true", "EVIDENCE_HEAD": "b" * 40}, clear=True), \
                    mock.patch.object(onboarding_check, "boot_and_install", side_effect=error), \
                    mock.patch.object(onboarding_check, "sweep_deferred_ui_dumps"), \
                    mock.patch.object(onboarding_check, "deferred_ui_dump_counts",
                                      return_value={"a": 1, "b": 0}), \
                    mock.patch.object(onboarding_check.time, "monotonic",
                                      side_effect=[10.0, 11.0, 12.0]), \
                    contextlib.redirect_stdout(output):
                status = onboarding_check.main(root)
        self.assertEqual(status, 1)
        status_line, _ = self.output_lines(output)
        self.assertEqual(
            status_line, "FAIL gate=onboarding_welcome_timeout_a elapsedSeconds=2.0")

        with tempfile.TemporaryDirectory() as directory:
            root = self.owned_root(directory)
            output = io.StringIO()
            with mock.patch.dict(os.environ, {
                    "GITHUB_ACTIONS": "true", "EVIDENCE_HEAD": "c" * 40}, clear=True), \
                    mock.patch.object(onboarding_check, "boot_and_install"), \
                    mock.patch.object(onboarding_check, "onboarding"), \
                    mock.patch.object(onboarding_check, "sweep_deferred_ui_dumps"), \
                    mock.patch.object(onboarding_check, "deferred_ui_dump_counts",
                                      return_value={"a": 1, "b": 0}), \
                    mock.patch.object(onboarding_check.time, "monotonic",
                                      side_effect=[20.0, 21.0, 22.0, 23.0]), \
                    contextlib.redirect_stdout(output):
                status = onboarding_check.main(root)
        self.assertEqual(status, 1)
        status_line, _ = self.output_lines(output)
        self.assertEqual(
            status_line, "FAIL gate=ui_cleanup_incomplete_a elapsedSeconds=3.0")
        self.assertEqual(
            onboarding_check.closed_status_gate("synthetic_arbitrary_a", False),
            "unclassified_controller_failure",
        )

    def test_trace_is_bounded_strict_and_contains_no_ui_payload(self):
        trace = onboarding_check.ClosedOnboardingTrace()
        for index in range(onboarding_check.MAX_TRACE_EVENTS + 3):
            trace("unknown", "a", 0, 0.0, float(index))
        payload = onboarding_check.validated_trace_payload(trace)
        self.assertEqual(len(payload["events"]), onboarding_check.MAX_TRACE_EVENTS)
        self.assertEqual(payload["droppedEvents"], 3)
        self.assertEqual(payload["counts"]["unknown"], onboarding_check.MAX_TRACE_EVENTS + 3)
        self.assertTrue(all(event["side"] == "a" for event in payload["events"]))
        serialized = json.dumps(payload)
        for forbidden in ("Your nickname", "Public room", "<?xml", "/data/local/tmp",
                          "emulator-5554"):
            self.assertNotIn(forbidden, serialized)
        with self.assertRaises(ValueError):
            trace("not_allowlisted", "a", 0, 0.0, 0.0)
        with self.assertRaises(ValueError):
            trace("unknown", "a", 3, 0.0, 0.0)
        with self.assertRaises(ValueError):
            trace("unknown", "a", 0, float("inf"), 0.0)
        with self.assertRaises(ValueError):
            trace("unknown", "emulator-5554", 0, 0.0, 0.0)

    def test_trace_retains_late_closed_product_phase_when_ui_detail_is_full(self):
        trace = onboarding_check.ClosedOnboardingTrace()
        for index in range(onboarding_check.MAX_TRACE_EVENTS):
            trace("ui_command", "a", 1, 13.0, float(index))
        trace("remote_received", "b", 2, 0.0, 70.0)
        payload = onboarding_check.validated_trace_payload(trace)
        self.assertEqual(len(payload["events"]), onboarding_check.MAX_TRACE_EVENTS)
        self.assertEqual(payload["droppedEvents"], 1)
        self.assertEqual(payload["events"][-1]["event"], "remote_received")
        self.assertEqual(payload["counts"]["ui_command"], onboarding_check.MAX_TRACE_EVENTS)
        self.assertEqual(payload["counts"]["remote_received"], 1)

    def test_trace_updates_are_thread_safe_and_keep_head_plus_tail(self):
        trace = onboarding_check.ClosedOnboardingTrace()

        def emit(worker):
            for index in range(100):
                trace("ui_command", "a" if worker % 2 == 0 else "b",
                      1 + worker % 2, 13.0, float(index))

        with ThreadPoolExecutor(max_workers=4) as pool:
            futures = [pool.submit(emit, worker) for worker in range(4)]
            for future in futures:
                future.result()
        payload = onboarding_check.validated_trace_payload(trace)
        self.assertEqual(payload["counts"]["ui_command"], 400)
        self.assertEqual(len(payload["events"]), onboarding_check.MAX_TRACE_EVENTS)
        self.assertEqual(payload["droppedEvents"], 400 - onboarding_check.MAX_TRACE_EVENTS)
        head = list(payload["events"][:onboarding_check.TRACE_PREFIX_EVENTS])
        trace("remote_received", "b", 2, 0.0, 101.0)
        payload = onboarding_check.validated_trace_payload(trace)
        self.assertEqual(payload["events"][:onboarding_check.TRACE_PREFIX_EVENTS], head)
        self.assertEqual(payload["events"][-1]["event"], "remote_received")
        self.assertEqual(payload["counts"]["remote_received"], 1)

    def test_controller_and_runner_have_no_media_path(self):
        controller = (HERE / "onboarding_check.py").read_text()
        runner = (HERE / "run-onboarding.sh").read_text()
        combined = (controller + runner).casefold()
        for forbidden in ("screenrecord", "host_recorder", "localassemble",
                          "upload-artifact", "github_output"):
            self.assertNotIn(forbidden, combined)
        self.assertIn("boot_and_install", controller)
        self.assertIn("onboarding(", controller)
        self.assertNotIn("wait_fgs_active", controller)
        self.assertIn("emulator-5554", runner)
        self.assertNotIn("emulator-5556", runner)

        self.assertIn("from onboarding_check import empty_trace_payload", runner)
        self.assertNotIn("'ONBOARDING_TRACE {", runner)

    def test_workflow_labels_are_exact_and_mutually_exclusive(self):
        workflow = (HERE.parents[1] / ".github/workflows/fgs-emulator-evidence.yml").read_text()
        labels = ("fgs-emulator-diagnose", "fgs-onboarding-reviewed",
                  "fgs-emulator-reviewed", "fgs-emulator-diagnostic-reviewed")
        for label in labels:
            self.assertEqual(workflow.count(f"github.event.label.name == '{label}'"), 1)
        self.assertIn("bash -n tools/fgs-evidence/run-onboarding.sh", workflow)
        onboarding_job = workflow.split("\n  onboarding:\n", 1)[1].split(
            "\n  evidence:\n", 1)[0]
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository",
                      onboarding_job)
        self.assertIn("ref: ${{ github.event.pull_request.head.sha }}", onboarding_job)
        self.assertIn("needs: validate", onboarding_job)
        self.assertIn("timeout-minutes: 25", onboarding_job)
        self.assertIn("prepare.py", onboarding_job)
        self.assertIn("run-onboarding.sh", onboarding_job)
        self.assertIn("if: always()", onboarding_job)
        self.assertNotIn("upload-artifact", onboarding_job)
        self.assertNotIn("capture.py", onboarding_job)
        self.assertNotIn("run.sh", onboarding_job)
        self.assertNotIn("GITHUB_OUTPUT", onboarding_job)


if __name__ == "__main__":
    unittest.main()
