"""Offline synthetic checks; these never launch adb, an emulator or a network request."""
import hashlib
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as E
import zipfile

import capture
import diagnose
import localassemble
import prepare


class EvidenceChecks(unittest.TestCase):
    def archive(self, directory, name, entries):
        path = Path(directory) / name
        with zipfile.ZipFile(path, "w") as archive:
            for key, content in entries:
                archive.writestr(key, content)
        return path

    def test_exact_canonical_inventory_and_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(directory, "a.zip", [("artifact", b"synthetic")])
            prepare.verify_archive(path, {"artifact": hashlib.sha256(b"synthetic").hexdigest()})

    def test_canonical_hash_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(directory, "a.zip", [("artifact", b"changed")])
            with self.assertRaises(ValueError):
                prepare.verify_archive(path, {"artifact": "0"*64})

    def test_extra_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(directory, "a.zip", [("extra", b"x")])
            with self.assertRaises(ValueError):
                prepare.verify_archive(path, {})

    def test_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(directory, "a.zip", [("../artifact", b"x")])
            with self.assertRaises(ValueError):
                prepare.verify_archive(path, {"../artifact": hashlib.sha256(b"x").hexdigest()})

    def test_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            info = zipfile.ZipInfo("artifact")
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            path = self.archive(directory, "a.zip", [(info, b"target")])
            with self.assertRaises(ValueError):
                prepare.verify_archive(path, {"artifact": hashlib.sha256(b"target").hexdigest()})

    def test_unchanged_signing_payload_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            a = self.archive(directory, "a.apk", [("classes.dex", b"synthetic")])
            b = self.archive(directory, "b.apk", [("classes.dex", b"synthetic")])
            prepare.verify_payload(a, b)

    def test_changed_signing_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            a = self.archive(directory, "a.apk", [("classes.dex", b"synthetic")])
            b = self.archive(directory, "b.apk", [("classes.dex", b"changed")])
            with self.assertRaises(ValueError):
                prepare.verify_payload(a, b)

    def test_extra_signing_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            a = self.archive(directory, "a.apk", [("classes.dex", b"synthetic")])
            b = self.archive(directory, "b.apk", [("classes.dex", b"synthetic"), ("extra", b"x")])
            with self.assertRaises(ValueError):
                prepare.verify_payload(a, b)

    def test_adb_rejects_every_other_target_without_execution(self):
        with patch("capture.subprocess.run") as command:
            for serial in ("", "physical-example", "emulator-5558", "-d"):
                with self.assertRaises(ValueError):
                    capture.adb(serial, "shell", "echo", "synthetic")
            command.assert_not_called()

    def test_ui_select_does_not_accept_disabled_or_wrong_state(self):
        items = [E.Element("node", {"text": "Send in public", "enabled": "false"})]
        self.assertIsNone(capture.select(items, "Send in public"))
        self.assertIsNone(capture.select(items, "Quit Wesh"))

    def test_ui_select_accepts_actual_case_insensitive_label(self):
        node = E.Element("node", {"content-desc": "PUBLIC ROOM", "enabled": "true"})
        self.assertIs(capture.select([node], "Public room"), node)

    def test_invalid_ui_bounds_never_tap(self):
        with patch("capture.adb") as command:
            with self.assertRaises(capture.GateError) as failure:
                capture.tap(capture.SERIALS[0], E.Element("node", {"bounds": "bad"}))
            self.assertEqual(failure.exception.gate, "ui_action_a")
            command.assert_not_called()

    def test_failure_gates_are_allowlisted_and_never_accept_arbitrary_text(self):
        self.assertEqual(capture.gate_name("install", capture.SERIALS[1]), "install_b")
        self.assertEqual(capture.gate_name("ui_dump_parse", capture.SERIALS[0]),
                         "ui_dump_parse_a")
        self.assertEqual(capture.gate_name("scenario_budget"), "scenario_budget")
        for phase, serial in (("private/path", capture.SERIALS[0]),
                              ("ui_dump", capture.SERIALS[0]),
                              ("install", "physical-device"), ("private/path", None)):
            with self.assertRaises(ValueError):
                capture.gate_name(phase, serial)

    def test_ui_dump_retries_a_transient_failure_then_returns_real_nodes(self):
        xml = '<hierarchy><node text="Public room" enabled="true" /></hierarchy>'
        responses = ["", "", RuntimeError("transient"), "",
                     "", "", "", "", xml, ""]
        with patch("capture.adb", side_effect=responses) as command, \
             patch("capture.time.sleep"):
            items = capture.nodes(capture.SERIALS[0], capture.time.monotonic()+3)
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].get("text"), "Public room")
        self.assertEqual(command.call_count, 10)
        dump_paths = [call.args[-1] for call in command.call_args_list
                      if call.args[1:4] == ("shell", "uiautomator", "dump")]
        self.assertEqual(len(dump_paths), 2)
        self.assertNotEqual(dump_paths[0], dump_paths[1])

    def test_ui_dump_allows_platform_nine_second_idle_wait(self):
        xml = '<hierarchy><node text="Public room" enabled="true" /></hierarchy>'
        clock = [100.0]
        calls = []

        def command(serial, *args, timeout):
            calls.append((serial, args, timeout))
            if args[0:3] == ("shell", "uiautomator", "dump"):
                self.assertGreaterEqual(timeout, 9)
                clock[0] += 9
            if args[0:2] == ("exec-out", "cat"):
                return xml
            return ""

        with patch("capture.time.monotonic", side_effect=lambda: clock[0]), \
             patch("capture.adb", side_effect=command):
            items = capture.nodes(capture.SERIALS[0], 140.0)
        self.assertEqual(len(items), 1)
        self.assertEqual(clock[0], 109.0)
        self.assertTrue(all(0 < timeout <= capture.UI_DUMP_COMMAND_SECONDS
                            for _, _, timeout in calls))

    def test_ui_dump_caps_calls_to_snapshot_and_caller_deadlines(self):
        xml = '<hierarchy><node text="Public room" enabled="true" /></hierarchy>'

        def command(_serial, *args, timeout):
            if args[0:2] == ("exec-out", "cat"):
                return xml
            return ""

        with patch("capture.time.monotonic", return_value=100.0), \
             patch("capture.adb", side_effect=command) as adb_command:
            capture.nodes(capture.SERIALS[0], 105.0)
        self.assertTrue(all(0 < call.kwargs["timeout"] <= 5
                            for call in adb_command.call_args_list))
        dump_call = next(call for call in adb_command.call_args_list
                         if call.args[1:4] == ("shell", "uiautomator", "dump"))
        self.assertLessEqual(dump_call.kwargs["timeout"], 4)

    def test_ui_dump_two_timeouts_stay_inside_automatic_budget(self):
        clock = [100.0]
        dump_timeouts = []

        def command(_serial, *args, timeout):
            if args[0:3] == ("shell", "uiautomator", "dump"):
                dump_timeouts.append(timeout)
                clock[0] += timeout
                raise subprocess.TimeoutExpired("adb", timeout)
            return ""

        def sleep(seconds):
            clock[0] += seconds

        with patch("capture.time.monotonic", side_effect=lambda: clock[0]), \
             patch("capture.time.sleep", side_effect=sleep), \
             patch("capture.adb", side_effect=command):
            with self.assertRaises(capture.GateError) as failure:
                capture.nodes(capture.SERIALS[0], 200.0)
        self.assertEqual(failure.exception.gate, "ui_dump_command_timeout_a")
        self.assertEqual(len(dump_timeouts), capture.UI_DUMP_ATTEMPTS)
        self.assertTrue(all(timeout <= capture.UI_DUMP_COMMAND_SECONDS
                            for timeout in dump_timeouts))
        self.assertLessEqual(clock[0], 100.0 + capture.UI_SNAPSHOT_BUDGET_SECONDS)

    def test_expired_ui_deadline_runs_no_adb_and_has_named_gate(self):
        with patch("capture.adb") as command:
            with self.assertRaises(capture.GateError) as failure:
                capture.nodes(capture.SERIALS[0], capture.time.monotonic()-1)
        self.assertEqual(failure.exception.gate, "ui_dump_command_timeout_a")
        command.assert_not_called()

    def test_ui_dump_rejects_unverified_freshness_without_dumping(self):
        def command(_serial, *args, timeout):
            if args[0:4] == ("shell", "test", "!", "-e"):
                raise RuntimeError("not fresh")
            return ""

        with patch("capture.adb", side_effect=command) as adb_command, \
             patch("capture.time.sleep"):
            with self.assertRaises(capture.GateError) as failure:
                capture.nodes(capture.SERIALS[0], capture.time.monotonic()+2)
        self.assertEqual(failure.exception.gate, "ui_dump_stale_a")
        self.assertFalse(any(call.args[1:4] == ("shell", "uiautomator", "dump")
                             for call in adb_command.call_args_list))

    def test_ui_dump_rejects_missing_output_after_both_attempts(self):
        def command(_serial, *args, timeout):
            if args[0:3] == ("shell", "test", "-s"):
                raise RuntimeError("missing")
            return ""

        with patch("capture.adb", side_effect=command), patch("capture.time.sleep"):
            with self.assertRaises(capture.GateError) as failure:
                capture.nodes(capture.SERIALS[1], capture.time.monotonic()+2)
        self.assertEqual(failure.exception.gate, "ui_dump_output_missing_b")

    def test_ui_dump_classifies_command_timeout_error_read_and_parse(self):
        xml = '<hierarchy><node text="Public room" enabled="true" /></hierarchy>'
        cases = (
            ("timeout", "ui_dump_command_timeout_a"),
            ("error", "ui_dump_command_error_a"),
            ("read", "ui_dump_read_a"),
            ("parse", "ui_dump_parse_a"),
        )
        for kind, expected in cases:
            def command(_serial, *args, timeout, kind=kind):
                if args[0:3] == ("shell", "uiautomator", "dump"):
                    if kind == "timeout":
                        raise subprocess.TimeoutExpired("adb", timeout)
                    if kind == "error":
                        raise RuntimeError("private command detail")
                if args[0:2] == ("exec-out", "cat"):
                    if kind == "read":
                        raise RuntimeError("private read detail")
                    return "not xml" if kind == "parse" else xml
                return ""

            with self.subTest(kind=kind), patch("capture.adb", side_effect=command), \
                 patch("capture.time.sleep"):
                with self.assertRaises(capture.GateError) as failure:
                    capture.nodes(capture.SERIALS[0], capture.time.monotonic()+2)
                self.assertEqual(failure.exception.gate, expected)
                self.assertNotIn("private", str(failure.exception))

    def test_ui_dump_paths_are_unique_side_specific_and_always_cleaned(self):
        xml = '<hierarchy><node text="Public room" enabled="true" /></hierarchy>'
        calls = []

        def command(serial, *args, timeout):
            calls.append((serial, args))
            return xml if args[0:2] == ("exec-out", "cat") else ""

        with patch("capture.adb", side_effect=command):
            capture.nodes(capture.SERIALS[0], capture.time.monotonic()+2)
            capture.nodes(capture.SERIALS[1], capture.time.monotonic()+2)
        dump_paths = {serial: args[-1] for serial, args in calls
                      if args[0:3] == ("shell", "uiautomator", "dump")}
        self.assertRegex(dump_paths[capture.SERIALS[0]], r"wesh-fgs-window-a-\d{8}\.xml$")
        self.assertRegex(dump_paths[capture.SERIALS[1]], r"wesh-fgs-window-b-\d{8}\.xml$")
        self.assertNotEqual(*dump_paths.values())
        for serial, path in dump_paths.items():
            removals = [args for call_serial, args in calls if call_serial == serial and
                        args == ("shell", "rm", "-f", path)]
            self.assertEqual(len(removals), 2)

    def test_ui_dump_cleanup_failure_is_fail_closed_and_hides_raw_detail(self):
        xml = '<hierarchy><node text="Public room" enabled="true" /></hierarchy>'
        removals = 0

        def command(_serial, *args, timeout):
            nonlocal removals
            if args[0:3] == ("shell", "rm", "-f"):
                removals += 1
                if removals == 2:
                    raise RuntimeError("private cleanup detail")
            return xml if args[0:2] == ("exec-out", "cat") else ""

        with patch("capture.adb", side_effect=command):
            with self.assertRaises(capture.GateError) as failure:
                capture.nodes(capture.SERIALS[0], capture.time.monotonic()+2)
        self.assertEqual(failure.exception.gate, "ui_dump_cleanup_a")
        self.assertNotIn("private", str(failure.exception))

    def test_expired_command_deadline_runs_no_adb(self):
        with patch("capture.adb") as command:
            with self.assertRaises(capture.GateError) as failure:
                capture.deadline_adb(capture.SERIALS[0], "message_submit",
                                     capture.time.monotonic()-1, "shell", "true")
        self.assertEqual(failure.exception.gate, "message_submit_a")
        command.assert_not_called()

    def test_boot_timeout_cannot_fall_through_without_completed_property(self):
        with patch("capture.time.monotonic", side_effect=[0.0, 241.0]), patch("capture.adb") as command:
            with self.assertRaises(capture.GateError) as failure:
                capture.boot_and_install(capture.SERIALS[0], Path("synthetic.apk"))
        self.assertEqual(failure.exception.gate, "boot_timeout_a")
        command.assert_not_called()

    def test_host_recorder_capability_is_fail_closed(self):
        with patch("capture.adb") as command:
            with self.assertRaises(capture.GateError) as failure:
                capture.require_host_recorder(capture.SERIALS[1], capture.time.monotonic()-1)
        self.assertEqual(failure.exception.gate, "host_recorder_capability_b")
        command.assert_not_called()

    def test_host_recorder_requires_documented_help_contract(self):
        for response in ("OK\n", "screenrecord mp4 OK\n", "screenrecord webm\n"):
            with self.subTest(response=response), patch("capture.adb", return_value=response):
                with self.assertRaises(capture.GateError) as failure:
                    capture.require_host_recorder(capture.SERIALS[0], capture.time.monotonic()+1)
                self.assertEqual(failure.exception.gate, "host_recorder_capability_a")

    def test_host_recorder_start_is_console_scoped_and_webm_only(self):
        output = Path("/tmp/wesh-fgs-a.avd/console_out/recording-a.webm")
        with patch("capture.adb", return_value="OK\n") as command:
            capture.start_host_recorder(capture.SERIALS[0], output, capture.time.monotonic()+1)
        self.assertEqual(command.call_args.args[:6], (
            capture.SERIALS[0], "emu", "screenrecord", "start", "--time-limit",
            str(capture.RECORDING_LIMIT_SECONDS)))
        self.assertEqual(command.call_args.args[6], output.name)
        self.assertNotIn("shell", command.call_args.args)

    def test_host_recorder_rejects_wrong_or_relative_output_before_adb(self):
        with patch("capture.adb") as command:
            for path in (Path("recording-a.webm"), Path("/tmp/recording-a.mp4"),
                         Path("/tmp/recording-b.webm"),
                         Path("/tmp/other.avd/console_out/recording-a.webm")):
                with self.subTest(path=path), self.assertRaises(capture.GateError):
                    capture.start_host_recorder(capture.SERIALS[0], path, capture.time.monotonic()+1)
        command.assert_not_called()

    def test_controller_has_no_guest_screenrecord_fallback(self):
        source = Path(capture.__file__).read_text()
        self.assertNotIn('"shell", "screenrecord"', source)
        self.assertIn('"emu", "screenrecord"', source)

    def test_host_recording_inventory_requires_both_nonempty_regular_webms(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            recordings = []
            for side in ("a", "b"):
                path = root/f"wesh-fgs-{side}.avd"/"console_out"/f"recording-{side}.webm"
                path.parent.mkdir(parents=True)
                path.write_bytes(b"synthetic unit bytes")
                recordings.append(path)
            capture.wait_host_recordings(recordings, capture.time.monotonic()-200)
            recordings[0].unlink()
            with self.assertRaises(capture.GateError) as failure:
                capture.wait_host_recordings(recordings, capture.time.monotonic()-200)
            self.assertEqual(failure.exception.gate, "host_recording_file_a")

    def test_host_recording_wait_is_measured_from_last_start(self):
        with tempfile.TemporaryDirectory() as directory:
            recordings = []
            for side in ("a", "b"):
                path = Path(directory)/f"recording-{side}.webm"
                path.write_bytes(b"synthetic unit bytes")
                recordings.append(path)
            with patch("capture.time.monotonic", return_value=100.0), \
                 patch("capture.time.sleep") as sleep:
                capture.wait_host_recordings(recordings, 50.0)
            sleep.assert_called_once_with(140.0)

    def test_export_rolls_back_both_webms_when_second_rename_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/"evidence").mkdir()
            (root/"evidence"/"provenance.json").write_text("{}")
            (root/"evidence"/"attempt.json").write_text("{}")
            recordings = []
            for side in ("a", "b"):
                path = root/"avd"/f"wesh-fgs-{side}.avd"/"console_out"/f"recording-{side}.webm"
                path.parent.mkdir(parents=True)
                path.write_bytes(b"synthetic unit bytes")
                recordings.append(path)
            receipt = {"result": capture.SCENARIO_RESULT, "recordingsExported": False,
                       "failureGate": None}
            original_rename = Path.rename

            def fail_second(path, destination):
                if path.name == "recording-b.webm":
                    raise OSError("synthetic rename failure")
                return original_rename(path, destination)

            with patch.object(Path, "rename", new=fail_second):
                with self.assertRaises(capture.GateError) as failure:
                    capture.export_host_recordings(root, recordings, receipt)
            self.assertEqual(failure.exception.gate, "evidence_export")
            self.assertFalse(any((root/"evidence").glob("*.webm")))
            self.assertTrue(all(not path.exists() for path in recordings))
            failed = json.loads((root/"evidence"/"attempt.json").read_text())
            self.assertEqual(failed["result"], "NOT_COMPLETED")
            self.assertFalse(failed["recordingsExported"])
            self.assertEqual(failed["failureGate"], "evidence_export")

    def test_export_atomically_replaces_receipts_with_complete_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/"evidence").mkdir()
            (root/"evidence"/"provenance.json").write_text('{"synthetic":true}')
            (root/"evidence"/"attempt.json").write_text("{}")
            recordings = []
            for side in ("a", "b"):
                path = root/"avd"/f"wesh-fgs-{side}.avd"/"console_out"/f"recording-{side}.webm"
                path.parent.mkdir(parents=True)
                path.write_bytes(b"synthetic unit bytes")
                recordings.append(path)
            receipt = {"result": capture.SCENARIO_RESULT, "recordingsExported": False,
                       "failureGate": None}
            capture.export_host_recordings(root, recordings, receipt)
            self.assertEqual({path.name for path in (root/"evidence").iterdir()}, {
                "recording-a.webm", "recording-b.webm", "provenance.json",
                "attempt.json", "SHA256SUMS"})
            self.assertFalse((root/"evidence-ready").exists())
            self.assertFalse((root/"evidence-failure-receipts").exists())
            complete = json.loads((root/"evidence"/"attempt.json").read_text())
            self.assertTrue(complete["recordingsExported"])
            self.assertIsNone(complete["failureGate"])

    def test_activity_guard_requires_resumed_marker_and_package_on_same_line(self):
        misleading = f"mResumedActivity: other.package/.Main\nRecent task: {capture.PACKAGE}"
        with patch("capture.adb", return_value=misleading):
            self.assertFalse(capture.activity_resumed(capture.SERIALS[0]))

    def test_bluetooth_timeout_is_classified_per_owned_emulator(self):
        with patch("capture.adb") as command:
            with self.assertRaises(capture.GateError) as failure:
                capture.wait_bluetooth_on(capture.SERIALS[1], capture.time.monotonic()-1)
        self.assertEqual(failure.exception.gate, "bluetooth_on_timeout_b")
        command.assert_not_called()

    def test_fgs_timeout_is_classified_per_owned_emulator(self):
        with patch("capture.fgs_active", return_value=False), patch("capture.time.sleep"):
            with self.assertRaises(capture.GateError) as failure:
                capture.wait_fgs_active(capture.SERIALS[0], capture.time.monotonic()+0.001)
        self.assertEqual(failure.exception.gate, "fgs_timeout_a")

    def test_recorded_relaunch_contract_preserves_real_setup_window(self):
        self.assertEqual(capture.RECORDING_LIMIT_SECONDS, 180)
        self.assertEqual(capture.SCENARIO_BUDGET_SECONDS, 165)
        self.assertIn("services_stopped_for_recorded_relaunch", capture.PREFLIGHT_STEPS)
        self.assertEqual(capture.SCENARIO_STEPS[:2], (
            "actual_onboarding_permissions_completed", "both_apps_display_real_peer"))

    def test_real_first_public_disclosure_requires_confirmation(self):
        title = E.Element("node", {"text": "Send to the public room?", "enabled": "true"})
        self.assertEqual(capture.public_send_state([title], "synthetic"), "confirm")

    def test_outgoing_bubble_alone_does_not_prove_send_completion(self):
        bubble = E.Element("node", {"text": "synthetic", "enabled": "true"})
        field = E.Element("node", {"class": "android.widget.EditText", "text": "synthetic"})
        self.assertEqual(capture.public_send_state([bubble, field], "synthetic"), "pending")

    def test_completed_public_send_requires_draft_clear_and_bubble(self):
        bubble = E.Element("node", {"text": "synthetic", "enabled": "true"})
        field = E.Element("node", {"class": "android.widget.EditText", "text": ""})
        self.assertEqual(capture.public_send_state([bubble, field], "synthetic"), "submitted")

    def test_diagnostic_identifies_missing_commands_without_paths(self):
        commands = {"sdkmanager": False}
        self.assertEqual(diagnose.missing_preconditions(commands, True, 12, 12, 4),
                         ["sdkmanager_missing"])

    def test_diagnostic_reports_kvm_and_resources_separately(self):
        commands = {"sdkmanager": True}
        self.assertEqual(diagnose.missing_preconditions(commands, False, 10, 8, 2),
                         ["kvm_missing", "disk_below_11_gib", "available_memory_below_10_gib", "cpu_count_below_4"])

    def test_diagnostic_does_not_treat_supported_resources_as_missing(self):
        commands = {"sdkmanager": True}
        self.assertEqual(diagnose.missing_preconditions(commands, True, 12, 12, 4), [])

    def test_diagnostic_missing_package_has_no_path_output(self):
        self.assertIsNone(diagnose.installed_revision(Path("synthetic-missing-package.xml")))

    def test_input_method_visibility_parses_actual_boolean_fields(self):
        self.assertIs(capture.parse_ime_visibility("  mInputShown=true\n  mShowRequested=true\n"), True)
        self.assertIs(capture.parse_ime_visibility("  mInputShown=false\n  mShowRequested=false\n"), False)

    def test_missing_or_ambiguous_input_method_visibility_is_unknown(self):
        self.assertIsNone(capture.parse_ime_visibility("  mShowRequested=true\n"))
        self.assertIsNone(capture.parse_ime_visibility("mInputShown=true\nmInputShown=false\n"))

    def test_back_is_sent_only_for_confirmed_visible_keyboard(self):
        with patch("capture.adb", return_value="  mInputShown=true\n") as command:
            capture.hide_keyboard_if_shown(capture.SERIALS[0])
            self.assertEqual(command.call_args_list[-1].args,
                             (capture.SERIALS[0], "shell", "input", "keyevent", "4"))
            self.assertEqual(command.call_count, 2)

    def test_no_back_when_keyboard_is_not_visible(self):
        with patch("capture.adb", return_value="  mInputShown=false\n") as command:
            capture.hide_keyboard_if_shown(capture.SERIALS[0])
            self.assertEqual(command.call_count, 1)

    def test_unknown_keyboard_state_aborts_without_back(self):
        with patch("capture.adb", return_value="  mShowRequested=true\n") as command:
            with self.assertRaises(capture.ImeVisibilityUnknown):
                capture.hide_keyboard_if_shown(capture.SERIALS[0])
            self.assertEqual(command.call_count, 1)

    def test_keyboard_query_scopes_each_owned_emulator(self):
        result = subprocess.CompletedProcess([], 0, b"  mInputShown=false\n", b"")
        with patch("capture.subprocess.run", return_value=result) as command:
            for serial in capture.SERIALS:
                capture.hide_keyboard_if_shown(serial)
            self.assertEqual([call.args[0] for call in command.call_args_list], [
                ["adb", "-s", serial, "shell", "dumpsys", "input_method"] for serial in capture.SERIALS])

    def test_keyboard_query_rejects_every_other_target_before_execution(self):
        with patch("capture.subprocess.run") as command:
            with self.assertRaises(ValueError):
                capture.hide_keyboard_if_shown("emulator-5558")
            command.assert_not_called()

    def test_ci_scenario_result_does_not_claim_video_validation(self):
        self.assertEqual(capture.SCENARIO_RESULT, "SCENARIO_PASS_RECORDINGS_AWAIT_LOCAL_VALIDATION")

    def test_local_coverage_preserves_strict_quit_boundary(self):
        localassemble.require_coverage([165.0, 166.0], 165.0)
        with self.assertRaises(localassemble.ValidationFailure):
            localassemble.require_coverage([164.99, 166.0], 165.0)

    def test_local_coverage_rejects_missing_and_nonfinite_durations(self):
        for durations in ([180.0], [float("nan"), 180.0], [float("inf"), 180.0]):
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.require_coverage(durations, 120.0)

    def test_local_probe_rejects_invalid_media_duration(self):
        with patch("localassemble.run_tool", return_value='{"format":{"duration":"NaN"}}'):
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.probe_duration("synthetic-tool", Path("synthetic.mp4"))

    def test_local_json_rejects_duplicate_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"synthetic.json"
            path.write_text('{"result":1,"result":2}')
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.unique_json(path)

    def local_bundle(self, directory, source_head="1"*40):
        """Unit metadata only; these bytes are not media or transport evidence."""
        root = Path(directory)
        provenance = {"source": prepare.SOURCE, "unsignedApkSHA256": prepare.FILES[prepare.APK],
                      "attestationsVerified": 11, "apkPayloadUnchanged": True}
        attempt = {"evidenceSource": source_head, "result": capture.SCENARIO_RESULT,
                   "captureSource": capture.CAPTURE_SOURCE,
                   "recordingsExported": True, "finalVideoQualified": False,
                   "notPhysicalEvidence": True, "notPlaySigned": True, "failureGate": None,
                   "preflightEvents": [{"step": step, "elapsedSeconds": number+1}
                                       for number, step in enumerate(capture.PREFLIGHT_STEPS)],
                   "events": [{"step": step, "elapsedSeconds": number+1}
                              for number, step in enumerate(localassemble.EVENTS)]}
        attempt["events"][0]["elapsedSeconds"] = 0.0
        (root/"provenance.json").write_text(json.dumps(provenance))
        (root/"attempt.json").write_text(json.dumps(attempt))
        for name in localassemble.RECORDINGS:
            (root/name).write_bytes(b"synthetic unit bytes, not an OS recording")
        self.rehash_bundle(root)
        return root

    def rehash_bundle(self, root):
        (root/"SHA256SUMS").write_text("".join(
            f"{prepare.digest(root/name)}  {name}\n" for name in sorted(localassemble.HASHED_INPUTS)))

    def test_local_metadata_gate_requires_exact_controller_and_complete_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.local_bundle(directory)
            last, checksums = localassemble.validate_inputs(root, "1"*40)
            self.assertEqual(last, 7)
            self.assertEqual(set(checksums), localassemble.HASHED_INPUTS)
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.validate_inputs(root, "2"*40)

    def test_local_metadata_gate_rejects_modified_recording(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.local_bundle(directory)
            (root/"recording-a.webm").write_bytes(b"changed unit bytes")
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.validate_inputs(root, "1"*40)

    def test_local_metadata_gate_rejects_missing_or_reordered_preflight(self):
        for mutation in ("missing", "reordered"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = self.local_bundle(directory)
                attempt = json.loads((root/"attempt.json").read_text())
                if mutation == "missing":
                    attempt["preflightEvents"].pop()
                else:
                    attempt["preflightEvents"][0], attempt["preflightEvents"][1] = (
                        attempt["preflightEvents"][1], attempt["preflightEvents"][0])
                (root/"attempt.json").write_text(json.dumps(attempt))
                self.rehash_bundle(root)
                with self.assertRaisesRegex(localassemble.ValidationFailure,
                                            "preflight_event_sequence"):
                    localassemble.validate_inputs(root, "1"*40)

    def test_preflight_clock_is_distinct_from_zero_based_scenario_clock(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.local_bundle(directory)
            attempt = json.loads((root/"attempt.json").read_text())
            for number, event in enumerate(attempt["preflightEvents"]):
                event["elapsedSeconds"] = 180 + number
            self.assertEqual(attempt["events"][0]["elapsedSeconds"], 0.0)
            (root/"attempt.json").write_text(json.dumps(attempt))
            self.rehash_bundle(root)
            last, _ = localassemble.validate_inputs(root, "1"*40)
            self.assertEqual(last, 7)

    def test_local_metadata_gate_rejects_extra_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.local_bundle(directory)
            (root/"extra").write_bytes(b"synthetic")
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.validate_inputs(root, "1"*40)

    def test_local_metadata_gate_rejects_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.local_bundle(directory)
            (root/"recording-b.webm").unlink()
            (root/"recording-b.webm").symlink_to(root/"recording-a.webm")
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.validate_inputs(root, "1"*40)


if __name__ == "__main__":
    unittest.main()
