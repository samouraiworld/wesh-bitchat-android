"""Offline synthetic checks; these never launch adb, an emulator or a network request."""
import hashlib
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as E
import zipfile

import capture
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


if __name__ == "__main__":
    unittest.main()
