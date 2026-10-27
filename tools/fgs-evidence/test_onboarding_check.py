import contextlib
import importlib.util
import io
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

    def test_pass_uses_one_avd_real_onboarding_and_one_closed_status_line(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.owned_root(directory)
            output = io.StringIO()
            with mock.patch.dict(os.environ, {
                    "GITHUB_ACTIONS": "true", "EVIDENCE_HEAD": "a" * 40}, clear=True), \
                    mock.patch.object(onboarding_check, "boot_and_install") as boot, \
                    mock.patch.object(onboarding_check, "onboarding") as onboarding, \
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
        sweep.assert_called_once()
        self.assertEqual(output.getvalue(), "PASS gate=none elapsedSeconds=3.0\n")

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
        self.assertEqual(
            output.getvalue(), "FAIL gate=onboarding_welcome_timeout_a elapsedSeconds=2.0\n")

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
        self.assertEqual(
            output.getvalue(), "FAIL gate=onboarding_welcome_timeout_a elapsedSeconds=2.0\n")

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
        self.assertEqual(
            output.getvalue(), "FAIL gate=ui_cleanup_incomplete_a elapsedSeconds=3.0\n")
        self.assertEqual(
            onboarding_check.closed_status_gate("synthetic_arbitrary_a", False),
            "unclassified_controller_failure",
        )

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

    def test_workflow_labels_are_exact_and_mutually_exclusive(self):
        workflow = (HERE.parents[1] / ".github/workflows/fgs-emulator-evidence.yml").read_text()
        labels = ("fgs-emulator-diagnose", "fgs-onboarding-reviewed",
                  "fgs-emulator-reviewed")
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
