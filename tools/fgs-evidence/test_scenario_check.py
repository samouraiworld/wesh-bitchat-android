import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import capture
import scenario_check


HERE = Path(__file__).resolve().parent


class ImmediateFuture:
    def __init__(self, function, *args):
        self.value = function(*args)

    def result(self):
        return self.value


class ImmediateExecutor:
    def __init__(self, _workers):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def submit(self, function, *args):
        return ImmediateFuture(function, *args)


class ScenarioCheckTests(unittest.TestCase):
    def owned_root(self, directory):
        root = Path(directory)
        (root / scenario_check.APK_NAME).write_bytes(b"synthetic canonical fixture")
        (root / scenario_check.UI_READER_NAME).write_bytes(b"synthetic reader fixture")
        return root

    def output_lines(self, output):
        lines = output.getvalue().splitlines()
        self.assertEqual(len(lines), 2)
        self.assertTrue(lines[1].startswith(scenario_check.TRACE_PREFIX))
        trace = json.loads(lines[1][len(scenario_check.TRACE_PREFIX):])
        return lines[0], trace

    def test_pass_runs_exact_shared_product_steps_without_recording_event(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.owned_root(directory)
            output = io.StringIO()
            clock = [0.0]
            seen = []

            def scenario(deadline, event, observer):
                self.assertEqual(deadline, capture.SCENARIO_BUDGET_SECONDS)
                seen.append("scenario_started")
                for step in scenario_check.PRODUCT_STEPS:
                    clock[0] += 1.0
                    event(step)
                observer("room_open_tapped", "a", 0, 0.0, 3.0)
                observer("remote_received", "b", 0, 0.0, 4.0)

            with mock.patch.dict(os.environ, {
                    "GITHUB_ACTIONS": "true", "EVIDENCE_HEAD": "a" * 40}, clear=True), \
                    mock.patch.object(scenario_check, "ThreadPoolExecutor", ImmediateExecutor), \
                    mock.patch.object(scenario_check, "boot_and_install") as boot, \
                    mock.patch.object(scenario_check, "configure_ui_reader") as configure, \
                    mock.patch.object(scenario_check, "ui_reader_healthcheck") as health, \
                    mock.patch.object(scenario_check, "cleanup_ui_reader") as cleanup_reader, \
                    mock.patch.object(scenario_check, "run_product_scenario",
                                      side_effect=scenario), \
                    mock.patch.object(scenario_check, "sweep_deferred_ui_dumps") as sweep, \
                    mock.patch.object(scenario_check, "deferred_ui_dump_counts",
                                      return_value={"a": 0, "b": 0}), \
                    mock.patch.object(scenario_check.time, "monotonic",
                                      side_effect=lambda: clock[0]), \
                    contextlib.redirect_stdout(output):
                status = scenario_check.main(root)

        self.assertEqual(status, 0)
        self.assertEqual(boot.call_count, 2)
        self.assertEqual(configure.call_count, 2)
        self.assertEqual(health.call_count, 2)
        self.assertEqual(cleanup_reader.call_count, 2)
        sweep.assert_called_once()
        self.assertEqual(seen, ["scenario_started"])
        self.assertNotIn("recording_commands_started", scenario_check.PRODUCT_STEPS)
        status_line, trace = self.output_lines(output)
        self.assertEqual(status_line, "PASS gate=none elapsedSeconds=8.0")
        self.assertEqual(trace["counts"]["scenario_started"], 1)
        self.assertEqual(trace["counts"]["room_open_tapped"], 1)
        self.assertEqual(trace["counts"]["remote_received"], 1)
        self.assertEqual(trace["schemaVersion"], 2)
        self.assertEqual(
            [(entry["event"], entry["side"]) for entry in trace["events"]],
            [("scenario_started", "global"), ("room_open_tapped", "a"),
             ("remote_received", "b")],
        )

    def test_reader_healthchecks_both_sides_before_first_product_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.owned_root(directory)
            output = io.StringIO()
            calls = []

            def boot(serial, _apk):
                calls.append(("boot", serial))

            def configure(serial, reader, _deadline):
                self.assertEqual(reader, root / scenario_check.UI_READER_NAME)
                calls.append(("configure", serial))

            def health(serial, _deadline):
                calls.append(("health", serial))

            def scenario(_deadline, _event, observer=None):
                calls.append(("scenario",))
                raise capture.GateError("message_receive", capture.SERIALS[0])

            with mock.patch.dict(os.environ, {
                    "GITHUB_ACTIONS": "true", "EVIDENCE_HEAD": "c" * 40}, clear=True), \
                    mock.patch.object(scenario_check, "ThreadPoolExecutor", ImmediateExecutor), \
                    mock.patch.object(scenario_check, "boot_and_install", side_effect=boot), \
                    mock.patch.object(scenario_check, "configure_ui_reader",
                                      side_effect=configure), \
                    mock.patch.object(scenario_check, "ui_reader_healthcheck",
                                      side_effect=health), \
                    mock.patch.object(scenario_check, "cleanup_ui_reader"), \
                    mock.patch.object(scenario_check, "run_product_scenario",
                                      side_effect=scenario), \
                    mock.patch.object(scenario_check, "sweep_deferred_ui_dumps"), \
                    mock.patch.object(scenario_check, "deferred_ui_dump_counts",
                                      return_value={"a": 0, "b": 0}), \
                    mock.patch.object(scenario_check.time, "monotonic", return_value=0.0), \
                    contextlib.redirect_stdout(output):
                status = scenario_check.main(root)
        self.assertEqual(status, 1)
        self.assertEqual(calls, [
            ("boot", capture.SERIALS[0]), ("boot", capture.SERIALS[1]),
            ("configure", capture.SERIALS[0]), ("configure", capture.SERIALS[1]),
            ("health", capture.SERIALS[0]), ("health", capture.SERIALS[1]),
            ("scenario",),
        ])

    def test_failed_reader_healthcheck_never_launches_product_and_cleans_both_sides(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.owned_root(directory)
            output = io.StringIO()
            failure = capture.GateError("ui_reader_root_null", capture.SERIALS[1])
            with mock.patch.dict(os.environ, {
                    "GITHUB_ACTIONS": "true", "EVIDENCE_HEAD": "d" * 40}, clear=True), \
                    mock.patch.object(scenario_check, "ThreadPoolExecutor", ImmediateExecutor), \
                    mock.patch.object(scenario_check, "boot_and_install"), \
                    mock.patch.object(scenario_check, "configure_ui_reader"), \
                    mock.patch.object(scenario_check, "ui_reader_healthcheck",
                                      side_effect=[None, failure]), \
                    mock.patch.object(scenario_check, "cleanup_ui_reader") as cleanup, \
                    mock.patch.object(scenario_check, "run_product_scenario") as scenario, \
                    mock.patch.object(scenario_check, "sweep_deferred_ui_dumps"), \
                    mock.patch.object(scenario_check, "deferred_ui_dump_counts",
                                      return_value={"a": 0, "b": 0}), \
                    mock.patch.object(scenario_check.time, "monotonic", return_value=0.0), \
                    contextlib.redirect_stdout(output):
                status = scenario_check.main(root)
        self.assertEqual(status, 1)
        scenario.assert_not_called()
        self.assertEqual(cleanup.call_count, 2)
        status_line, trace = self.output_lines(output)
        self.assertEqual(status_line, "FAIL gate=ui_reader_root_null_b elapsedSeconds=0.0")
        self.assertEqual(trace, scenario_check.empty_trace_payload())

    def test_primary_gate_survives_cleanup_failure_and_output_is_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.owned_root(directory)
            output = io.StringIO()
            failure = capture.GateError("message_receive", capture.SERIALS[1])
            clock = [0.0]

            def fail_scenario(*_args, **_kwargs):
                clock[0] = 12.0
                raise failure

            with mock.patch.dict(os.environ, {
                    "GITHUB_ACTIONS": "true", "EVIDENCE_HEAD": "b" * 40}, clear=True), \
                    mock.patch.object(scenario_check, "ThreadPoolExecutor", ImmediateExecutor), \
                    mock.patch.object(scenario_check, "boot_and_install"), \
                    mock.patch.object(scenario_check, "configure_ui_reader"), \
                    mock.patch.object(scenario_check, "ui_reader_healthcheck"), \
                    mock.patch.object(scenario_check, "cleanup_ui_reader"), \
                    mock.patch.object(scenario_check, "run_product_scenario",
                                      side_effect=fail_scenario), \
                    mock.patch.object(scenario_check, "sweep_deferred_ui_dumps",
                                      side_effect=OSError("private cleanup detail")), \
                    mock.patch.object(scenario_check.time, "monotonic",
                                      side_effect=lambda: clock[0]), \
                    contextlib.redirect_stdout(output):
                status = scenario_check.main(root)
        self.assertEqual(status, 1)
        status_line, trace = self.output_lines(output)
        self.assertEqual(status_line, "FAIL gate=message_receive_b elapsedSeconds=12.0")
        self.assertEqual(trace["counts"]["scenario_started"], 1)
        self.assertEqual(trace["events"], [{
            "allocatedSeconds": 0.0, "attempt": 0,
            "elapsedSeconds": 0.0, "event": "scenario_started", "side": "global",
        }])
        self.assertNotIn("private cleanup detail", output.getvalue())

    def test_runner_and_workflow_are_non_media_and_label_is_exact(self):
        runner = (HERE / "run-scenario.sh").read_text()
        controller = (HERE / "scenario_check.py").read_text()
        combined = (runner + controller).casefold()
        for forbidden in ("screenrecord", "host_recorder", "localassemble",
                          "upload-artifact", "github_output", "recording_commands_started"):
            self.assertNotIn(forbidden, combined)
        self.assertIn("wesh-scenario-a", runner)
        self.assertIn("wesh-scenario-b", runner)
        self.assertIn("run_product_scenario", controller)

        workflow = (HERE.parents[1] / ".github/workflows/fgs-emulator-evidence.yml").read_text()
        labels = ("fgs-emulator-diagnose", "fgs-onboarding-reviewed",
                  "fgs-scenario-reviewed", "fgs-emulator-reviewed")
        for label in labels:
            self.assertEqual(workflow.count(
                f"github.event.label.name == '{label}'"), 1)
        scenario_job = workflow.split("\n  scenario:\n", 1)[1].split(
            "\n  evidence:\n", 1)[0]
        self.assertIn("timeout-minutes: 25", scenario_job)
        self.assertIn("ref: ${{ github.event.pull_request.head.sha }}", scenario_job)
        self.assertIn("run-scenario.sh", scenario_job)
        self.assertIn("build-ui-reader.sh", runner)
        self.assertIn("platforms;android-35", runner)
        self.assertIn("build-tools;37.0.0", runner)
        self.assertIn("if: always()", scenario_job)
        self.assertNotIn("upload-artifact", scenario_job)
        self.assertNotIn("GITHUB_OUTPUT", scenario_job)
        self.assertNotIn("run.sh", scenario_job)


if __name__ == "__main__":
    unittest.main()
