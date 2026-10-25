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
            with self.assertRaises(ValueError):
                capture.tap(capture.SERIALS[0], E.Element("node", {"bounds": "bad"}))
            command.assert_not_called()

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
                   "recordingsExported": True, "finalVideoQualified": False,
                   "notPhysicalEvidence": True, "notPlaySigned": True,
                   "events": [{"step": step, "elapsedSeconds": number+1}
                              for number, step in enumerate(localassemble.EVENTS)]}
        (root/"provenance.json").write_text(json.dumps(provenance))
        (root/"attempt.json").write_text(json.dumps(attempt))
        for name in localassemble.RECORDINGS:
            (root/name).write_bytes(b"synthetic unit bytes, not an OS recording")
        (root/"SHA256SUMS").write_text("".join(
            f"{prepare.digest(root/name)}  {name}\n" for name in sorted(localassemble.HASHED_INPUTS)))
        return root

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
            (root/"recording-a.mp4").write_bytes(b"changed unit bytes")
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.validate_inputs(root, "1"*40)

    def test_local_metadata_gate_rejects_extra_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.local_bundle(directory)
            (root/"extra").write_bytes(b"synthetic")
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.validate_inputs(root, "1"*40)

    def test_local_metadata_gate_rejects_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.local_bundle(directory)
            (root/"recording-b.mp4").unlink()
            (root/"recording-b.mp4").symlink_to(root/"recording-a.mp4")
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.validate_inputs(root, "1"*40)


if __name__ == "__main__":
    unittest.main()
