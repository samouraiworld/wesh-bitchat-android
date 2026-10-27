"""Offline synthetic checks; these never launch adb, an emulator or a network request."""
from contextlib import ExitStack
import hashlib
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as E
import zipfile

import capture
import diagnose
import localassemble
import prepare


class ImmediateFuture:
    def __init__(self, operation, args):
        try:
            self.value = operation(*args)
            self.error = None
        except BaseException as error:
            self.value = None
            self.error = error

    def result(self):
        if self.error is not None:
            raise self.error
        return self.value


class ImmediateExecutor:
    def __init__(self, _workers):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def submit(self, operation, *args):
        return ImmediateFuture(operation, args)


class EvidenceChecks(unittest.TestCase):
    def setUp(self):
        with capture._UI_DUMP_DEFERRED_LOCK:
            for paths in capture._UI_DUMP_DEFERRED.values():
                paths.clear()
        with capture._UI_READER_LOCK:
            capture._UI_READER_PATHS.clear()
            capture._UI_READER_READY.clear()

    def archive(self, directory, name, entries):
        path = Path(directory) / name
        with zipfile.ZipFile(path, "w") as archive:
            for key, content in entries:
                archive.writestr(key, content)
        return path

    def test_canonical108_provenance_and_synthetic_names_are_exact(self):
        self.assertEqual(prepare.SOURCE, "753769a07fa9e62c87afead88ea10ef42b823788")
        self.assertEqual(prepare.TAG, "v1.0.0-beta.3-android.108")
        self.assertEqual(prepare.RUN, 40469284584)
        self.assertEqual(prepare.ARTIFACT, 12718483430)
        self.assertEqual(
            prepare.ZIP_HASH,
            "24b0145856ec8b05941f790e610adce4aec2a5292b8fd0112064fa05f2e01171",
        )
        self.assertEqual(prepare.FILES, {
            "BUILDINFO.json": "c7ed007f8b4f7add43c241e458140a3efc52fac7b55f2dcad5c81274c0668ad1",
            "NATIVE-SYMBOLS-BUILDINFO.json": "f193d1da01292a2360668c558e2dcd5a8d2c6b9e99ed42239b297a56a250aaa5",
            "NATIVE-SYMBOLS-SHA256SUMS": "a4719ef687ec75244d8b5ec9069434abce71a3c9914e9b8e73cb5c676aef9a7f",
            "SHA256SUMS.unsigned": "1352b029bc574c309c26c38b8599c92c4c382f17462dddeb081889be635b7031",
            "bitchat-android-arm64-unsigned.apk": "ce5dc4a97441bd8d56b0f39b2c5f3bb1b7312a7b63aee8f9172307fcd47c3877",
            "bitchat-android-armv7-unsigned.apk": "368ad573a6ae607e1fcdcf899ecd0fc8a05d46850fff2d5576022147ae472ddb",
            "bitchat-android-release-unsigned.aab": "bebedfa2482da03d30a6fccf3b169369d962e97547477efc0f2a1bb8204139b0",
            "bitchat-android-universal-unsigned.apk": "bdbd6a7e0d0ff3781dd7ac88a4fd9041591cd5b2cf711feeeead5a172f25cde0",
            "bitchat-android-x86-unsigned.apk": "9bcaa7e986ecb276740b0a3d569c3029231d21dea10f2e9ee83643ae5b107334",
            "bitchat-android-x86_64-unsigned.apk": "77fba4f4429517bb94906b5bd26cd23b541c183e49599779ae930ad426896dd0",
            "wesh-android-native-debug-symbols.zip": "bdcb64e04c3cc92f65eab91af2758e6a8c87b411d354b5f1658f76cfe14a9410",
        })
        self.assertEqual(
            (capture.MESSAGE_A, capture.MESSAGE_B, capture.MESSAGE_BACKGROUND),
            ("FGS-public-A-108", "FGS-public-B-108", "FGS-background-108"),
        )
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/fgs-emulator-evidence.yml").read_text()
        runner = (root / "tools/fgs-evidence/run.sh").read_text()
        self.assertIn("name: FGS canonical 108 emulator evidence", workflow)
        self.assertIn("name: fgs-canonical108-simulated-ble", workflow)
        self.assertIn("name: fgs-canonical108-incomplete-attempt", workflow)
        self.assertNotIn("canonical107", workflow)
        self.assertIn("wesh108-test-signed.apk", runner)
        self.assertNotIn("wesh107-test-signed.apk", runner)

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

    def test_select_action_ignores_heading_and_returns_actionable_ancestor(self):
        heading = E.Element("node", {
            "text": "Turn on Bluetooth", "enabled": "true", "clickable": "false",
            "bounds": "[20,20][180,60]", "class": "android.widget.TextView",
        })
        button = E.Element("node", {
            "text": "Turn on Bluetooth", "enabled": "true", "clickable": "true",
            "bounds": "[0,80][200,160]", "class": "android.widget.Button",
        })
        self.assertIs(capture.select_action([heading, button], "Turn on Bluetooth"), button)

        action = E.Element("node", {
            "enabled": "true", "clickable": "true", "bounds": "[0,0][200,80]",
            "class": "android.view.View",
        })
        nested_heading = E.SubElement(action, "node", {
            "text": "Turn on Bluetooth", "enabled": "true", "clickable": "false",
            "bounds": "[20,20][180,60]", "class": "android.widget.TextView",
        })
        self.assertIs(capture.select_action([action, nested_heading], "Turn on Bluetooth"), action)
        self.assertIsNone(capture.select_action([heading], "Turn on Bluetooth"))

    def notification_shade(self, include_wesh=True, expanded=False):
        root = E.Element("node", {"package": capture.SYSTEM_UI_PACKAGE})
        E.SubElement(root, "node", {
            "content-desc": "Expand", "enabled": "true", "clickable": "true",
            "bounds": "[0,0][10,10]", "package": capture.SYSTEM_UI_PACKAGE,
            "resource-id": capture.NOTIFICATION_EXPAND_RESOURCE_ID,
            "class": capture.NOTIFICATION_EXPAND_CLASS,
        })
        stack = E.SubElement(root, "node", {
            "package": capture.SYSTEM_UI_PACKAGE,
            "resource-id": capture.NOTIFICATION_STACK_RESOURCE_ID,
        })
        adb_card = E.SubElement(stack, "node", {
            "package": capture.SYSTEM_UI_PACKAGE,
            "resource-id": capture.NOTIFICATION_ROW_RESOURCE_ID,
        })
        E.SubElement(adb_card, "node", {
            "text": "ADB debugging", "package": capture.SYSTEM_UI_PACKAGE,
        })
        E.SubElement(adb_card, "node", {
            "content-desc": "Expand", "enabled": "true", "clickable": "true",
            "bounds": "[0,0][20,20]", "package": capture.SYSTEM_UI_PACKAGE,
            "resource-id": capture.NOTIFICATION_EXPAND_RESOURCE_ID,
            "class": capture.NOTIFICATION_EXPAND_CLASS,
        })
        if expanded:
            E.SubElement(adb_card, "node", {
                "text": "Quit Wesh", "enabled": "true", "clickable": "true",
                "bounds": "[0,20][20,40]", "package": capture.SYSTEM_UI_PACKAGE,
            })
        wesh_expand = None
        wesh_quit = None
        if include_wesh:
            wesh_card = E.SubElement(stack, "node", {
                "package": capture.SYSTEM_UI_PACKAGE,
                "resource-id": capture.NOTIFICATION_ROW_RESOURCE_ID,
            })
            E.SubElement(wesh_card, "node", {
                "text": "Mesh running — 1 peers", "package": capture.SYSTEM_UI_PACKAGE,
            })
            if expanded:
                wesh_quit = E.SubElement(wesh_card, "node", {
                    "text": "Quit Wesh", "enabled": "true", "clickable": "true",
                    "bounds": "[40,40][80,80]", "package": capture.SYSTEM_UI_PACKAGE,
                })
            else:
                wesh_expand = E.SubElement(wesh_card, "node", {
                    "content-desc": "Expand", "enabled": "true", "clickable": "true",
                    "bounds": "[20,20][40,40]", "package": capture.SYSTEM_UI_PACKAGE,
                    "resource-id": capture.NOTIFICATION_EXPAND_RESOURCE_ID,
                    "class": capture.NOTIFICATION_EXPAND_CLASS,
                })
        return list(root.iter("node")), wesh_expand, wesh_quit

    def test_quit_scopes_expand_and_action_to_wesh_card_then_rechecks_open_shade(self):
        collapsed, wesh_expand, _quit = self.notification_shade()
        expanded, _expand, wesh_quit = self.notification_shade(expanded=True)
        absent, _expand, _quit = self.notification_shade(include_wesh=False)
        observed = []
        with patch("capture.deadline_adb") as deadline_adb, \
             patch("capture.fgs_active", side_effect=[True, False]), \
             patch("capture.nodes", side_effect=[collapsed, expanded, absent]), \
             patch("capture.tap") as tap, \
             patch("capture.time.monotonic", return_value=100.0):
            capture.quit_via_notification(
                capture.SERIALS[0], 150.0,
                observer=lambda *values: observed.append(values), trace_started=0.0,
            )
        self.assertEqual(deadline_adb.call_count, 3)
        self.assertEqual([call.args[1] for call in tap.call_args_list], [
            wesh_expand, wesh_quit,
        ])
        self.assertEqual([entry[0] for entry in observed], [
            "quit_requested", "shade_opened", "notification_card_found",
            "expand_tapped", "quit_action_found", "quit_tapped", "service_stopped",
            "shade_opened", "notification_absent",
        ])
        self.assertTrue(all(entry[0] in capture.ONBOARDING_TRACE_EVENTS
                            for entry in observed))

    def test_quit_uses_scoped_action_without_expand_when_wesh_row_is_already_expanded(self):
        expanded, _expand, wesh_quit = self.notification_shade(expanded=True)
        absent, _expand, _quit = self.notification_shade(include_wesh=False)
        observed = []
        with patch("capture.deadline_adb") as deadline_adb, \
             patch("capture.fgs_active", side_effect=[True, False]), \
             patch("capture.nodes", side_effect=[expanded, absent]), \
             patch("capture.tap") as tap, \
             patch("capture.time.monotonic", return_value=100.0):
            capture.quit_via_notification(
                capture.SERIALS[0], 150.0,
                observer=lambda *values: observed.append(values), trace_started=0.0,
            )
        self.assertEqual(deadline_adb.call_count, 3)
        self.assertEqual([call.args[1] for call in tap.call_args_list], [wesh_quit])
        phases = [entry[0] for entry in observed]
        self.assertNotIn("expand_tapped", phases)
        self.assertEqual(phases, [
            "quit_requested", "shade_opened", "notification_card_found",
            "quit_action_found", "quit_tapped", "service_stopped", "shade_opened",
            "notification_absent",
        ])

    def test_quit_never_claims_notification_absent_when_shade_snapshot_is_collapsed(self):
        collapsed, _wesh_expand, _quit = self.notification_shade()
        expanded, _expand, _wesh_quit = self.notification_shade(expanded=True)
        app_only = [E.Element("node", {
            "package": capture.PACKAGE, "text": "Public room",
        })]
        observed = []
        with patch("capture.deadline_adb"), \
             patch("capture.fgs_active", side_effect=[True, False]), \
             patch("capture.nodes", side_effect=[collapsed, expanded, app_only]), \
             patch("capture.tap"), \
             patch("capture.time.monotonic", return_value=100.0):
            with self.assertRaises(capture.GateError) as failure:
                capture.quit_via_notification(
                    capture.SERIALS[0], 150.0,
                    observer=lambda *values: observed.append(values), trace_started=0.0,
                )
        self.assertEqual(failure.exception.gate, "notification_expand_a")
        self.assertNotIn("notification_absent", [entry[0] for entry in observed])

    def test_invalid_ui_bounds_never_tap(self):
        with patch("capture.adb") as command:
            for bounds in ("bad", "[0,0][0,20]", "[20,0][10,20]", "[0,20][20,10]"):
                with self.assertRaises(capture.GateError) as failure:
                    capture.tap(
                        capture.SERIALS[0], E.Element("node", {"bounds": bounds}))
                self.assertEqual(failure.exception.gate, "ui_action_a")
            command.assert_not_called()

    def test_failure_gates_are_allowlisted_and_never_accept_arbitrary_text(self):
        self.assertEqual(capture.gate_name("install", capture.SERIALS[1]), "install_b")
        self.assertEqual(capture.gate_name("ui_dump_parse", capture.SERIALS[0]),
                         "ui_dump_parse_a")
        self.assertEqual(capture.gate_name("ui_dump_prepare_cleanup", capture.SERIALS[1]),
                         "ui_dump_prepare_cleanup_b")
        self.assertEqual(capture.gate_name("scenario_budget"), "scenario_budget")
        for phase, serial in (("private/path", capture.SERIALS[0]),
                              ("ui_dump", capture.SERIALS[0]),
                              ("install", "physical-device"), ("private/path", None)):
            with self.assertRaises(ValueError):
                capture.gate_name(phase, serial)

    def test_ui_dump_retries_a_transient_failure_then_returns_real_nodes(self):
        xml = '<hierarchy><node text="Public room" enabled="true" /></hierarchy>'
        responses = ["", "", RuntimeError("transient"),
                     "", "", "", "", xml, ""]
        with patch("capture.adb", side_effect=responses) as command, \
             patch("capture.time.sleep"):
            items = capture.nodes(capture.SERIALS[0], capture.time.monotonic()+3)
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].get("text"), "Public room")
        self.assertEqual(command.call_count, 9)
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

    def test_ui_dump_observer_reports_only_closed_phases_and_timings(self):
        xml = '<hierarchy><node text="Private payload" enabled="true" /></hierarchy>'
        observed = []

        def command(_serial, *args, timeout):
            if args[0:2] == ("exec-out", "cat"):
                return xml
            return ""

        with patch("capture.time.monotonic", return_value=100.0), \
             patch("capture.adb", side_effect=command):
            capture.nodes(
                capture.SERIALS[0], 120.0,
                observer=lambda *values: observed.append(values),
            )
        self.assertEqual([event[0] for event in observed], [
            "ui_prepare", "ui_stale", "ui_command", "ui_output", "ui_read",
            "ui_parse", "ui_complete",
        ])
        for event, side, attempt, allocated, elapsed in observed:
            self.assertIn(event, capture.ONBOARDING_TRACE_EVENTS)
            self.assertEqual(side, "a")
            self.assertEqual(attempt, 1)
            self.assertGreaterEqual(allocated, 0)
            self.assertGreaterEqual(elapsed, 0)
        serialized = json.dumps(observed)
        self.assertNotIn("Private payload", serialized)
        self.assertNotIn("/data/local/tmp", serialized)
        self.assertNotIn("emulator-5554", serialized)

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
        self.assertLessEqual(dump_call.kwargs["timeout"], 5)

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
        self.assertRegex(
            dump_paths[capture.SERIALS[0]],
            r"wesh-fgs-window-a-[0-9a-f]{16}-\d{8}\.xml$",
        )
        self.assertRegex(
            dump_paths[capture.SERIALS[1]],
            r"wesh-fgs-window-b-[0-9a-f]{16}-\d{8}\.xml$",
        )
        self.assertNotEqual(*dump_paths.values())
        for serial, path in dump_paths.items():
            removals = [args for call_serial, args in calls if call_serial == serial and
                        args == ("shell", "rm", "-f", path)]
            self.assertEqual(len(removals), 2)

    def test_ui_dump_post_cleanup_failure_is_deferred_without_losing_snapshot(self):
        xml = '<hierarchy><node text="Public room" enabled="true" /></hierarchy>'
        removals = 0
        final_timeout = None

        def command(_serial, *args, timeout):
            nonlocal removals, final_timeout
            if args[0:3] == ("shell", "rm", "-f"):
                removals += 1
                if removals == 2:
                    final_timeout = timeout
                    raise OSError("private cleanup spawn detail")
            return xml if args[0:2] == ("exec-out", "cat") else ""

        with patch("capture.adb", side_effect=command):
            items = capture.nodes(capture.SERIALS[0], capture.time.monotonic()+2)
        self.assertEqual(len(items), 1)
        self.assertLessEqual(final_timeout, capture.UI_POST_CLEANUP_SECONDS)
        self.assertEqual(capture.deferred_ui_dump_counts(), {"a": 1, "b": 0})
        self.assertNotIn("private", json.dumps(capture.deferred_ui_dump_counts()))

    def test_ui_dump_post_cleanup_never_masks_primary_failure(self):
        removals = {}

        def command(_serial, *args, timeout):
            path = args[-1]
            if args[0:3] == ("shell", "rm", "-f"):
                removals[path] = removals.get(path, 0) + 1
                if removals[path] == 2:
                    raise OSError("private cleanup spawn detail")
            if args[0:2] == ("exec-out", "cat"):
                return "private malformed xml"
            return ""

        with patch("capture.adb", side_effect=command), patch("capture.time.sleep"):
            with self.assertRaises(capture.GateError) as failure:
                capture.nodes(capture.SERIALS[0], capture.time.monotonic()+3)
        self.assertEqual(failure.exception.gate, "ui_dump_parse_a")
        self.assertNotIn("private", str(failure.exception))
        self.assertEqual(capture.deferred_ui_dump_counts(), {"a": 2, "b": 0})

    def test_ui_dump_initial_cleanup_remains_strict_and_named(self):
        def command(_serial, *args, timeout):
            if args[0:3] == ("shell", "rm", "-f"):
                raise RuntimeError("private prepare detail")
            return ""

        with patch("capture.adb", side_effect=command), patch("capture.time.sleep"):
            with self.assertRaises(capture.GateError) as failure:
                capture.nodes(capture.SERIALS[1], capture.time.monotonic()+3)
        self.assertEqual(failure.exception.gate, "ui_dump_prepare_cleanup_b")
        self.assertNotIn("private", str(failure.exception))

    def test_ui_dump_sweep_uses_only_owned_paths_and_exports_counts_only(self):
        owned_a = "/data/local/tmp/wesh-fgs-window-a-0123456789abcdef-00000001.xml"
        owned_b = "/data/local/tmp/wesh-fgs-window-b-0123456789abcdef-00000001.xml"
        self.assertTrue(capture.defer_ui_dump_cleanup(capture.SERIALS[0], owned_a))
        self.assertTrue(capture.defer_ui_dump_cleanup(capture.SERIALS[1], owned_b))
        self.assertFalse(capture.defer_ui_dump_cleanup(
            capture.SERIALS[0], "/data/local/tmp/private.xml"))
        self.assertEqual(capture.deferred_ui_dump_counts(), {"a": 1, "b": 1})
        self.assertNotIn("wesh-fgs-window", json.dumps(capture.deferred_ui_dump_counts()))

        with patch("capture.adb", return_value="") as command:
            capture.sweep_deferred_ui_dumps(capture.time.monotonic()+6)
        self.assertEqual(command.call_count, 2)
        for call in command.call_args_list:
            self.assertEqual(call.args[1:4], ("shell", "rm", "-f"))
            self.assertTrue(all(capture.owned_ui_dump_path(call.args[0], path)
                                for path in call.args[4:]))
        self.assertEqual(capture.deferred_ui_dump_counts(), {"a": 0, "b": 0})

    def test_ui_dump_sweep_failure_is_bounded_and_keeps_only_count(self):
        owned = "/data/local/tmp/wesh-fgs-window-a-0123456789abcdef-00000001.xml"
        capture.defer_ui_dump_cleanup(capture.SERIALS[0], owned)
        with patch("capture.adb", side_effect=OSError("private sweep spawn detail")) as command:
            capture.sweep_deferred_ui_dumps(capture.time.monotonic()+6)
        self.assertEqual(command.call_count, 1)
        self.assertLessEqual(command.call_args.kwargs["timeout"],
                             capture.UI_POST_CLEANUP_SECONDS)
        self.assertEqual(capture.deferred_ui_dump_counts(), {"a": 1, "b": 0})
        self.assertNotIn("private", json.dumps(capture.deferred_ui_dump_counts()))

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

    def test_host_recorder_help_transport_is_fail_closed(self):
        with patch("capture.adb") as command:
            with self.assertRaises(capture.GateError) as failure:
                capture.require_host_recorder(capture.SERIALS[1], capture.time.monotonic()-1)
        self.assertEqual(failure.exception.gate, "host_recorder_help_transport_b")
        command.assert_not_called()

        with patch("capture.adb", side_effect=RuntimeError("synthetic transport failure")):
            with self.assertRaises(capture.GateError) as failure:
                capture.require_host_recorder(capture.SERIALS[0], capture.time.monotonic()+1)
        self.assertEqual(failure.exception.gate, "host_recorder_help_transport_a")

    def test_host_recorder_accepts_documented_verbose_help_contract(self):
        response = "    screenrecord    Records the emulator display\nOK\n"
        with patch("capture.adb", return_value=response) as command:
            capture.require_host_recorder(capture.SERIALS[0], capture.time.monotonic()+1)
        self.assertEqual(command.call_args.args, (capture.SERIALS[0], "emu", "help-verbose"))

    def test_host_recorder_rejects_incomplete_or_stale_help_contract(self):
        cases = {
            "missing_command": "    network    Network controls\nOK\n",
            "false_subword": "    notscreenrecord    Not the command\nOK\n",
            "missing_ok": "    screenrecord    Records display\n",
            "ok_not_terminal": "    screenrecord    Records display\nOK\ntruncated body\n",
            "ko_despite_ok": "    screenrecord    Records display\nKO: denied\nOK\n",
            "stale_parent_help": "screenrecord webm OK\n",
        }
        for name, response in cases.items():
            with self.subTest(name=name), patch("capture.adb", return_value=response):
                with self.assertRaises(capture.GateError) as failure:
                    capture.require_host_recorder(capture.SERIALS[0], capture.time.monotonic()+1)
                self.assertEqual(failure.exception.gate, "host_recorder_help_contract_a")

    def test_host_recorder_start_is_console_scoped_and_webm_only(self):
        output = Path("/tmp/wesh-fgs-a.avd/console_out/recording-a.webm")
        with patch("capture.adb", return_value="OK\n") as command:
            capture.start_host_recorder(capture.SERIALS[0], output, capture.time.monotonic()+1)
        self.assertEqual(capture.RECORDING_LIMIT_SECONDS, 180)
        self.assertEqual(command.call_args.args[:6], (
            capture.SERIALS[0], "emu", "screenrecord", "start", "--time-limit",
            str(capture.RECORDING_LIMIT_SECONDS)))
        self.assertEqual(command.call_args.args[6], output.name)
        self.assertNotIn("shell", command.call_args.args)

    def test_host_recorder_start_requires_terminal_ok_without_ko(self):
        output = Path("/tmp/wesh-fgs-a.avd/console_out/recording-a.webm")
        for name, response in {
                "ko_despite_ok": "KO: recorder unavailable\nOK\n",
                "ok_not_terminal": "OK\ntruncated body\n",
                "missing_ok": "recording started\n",
        }.items():
            with self.subTest(name=name), patch("capture.adb", return_value=response):
                with self.assertRaises(capture.GateError) as failure:
                    capture.start_host_recorder(
                        capture.SERIALS[0], output, capture.time.monotonic()+1)
                self.assertEqual(failure.exception.gate, "host_recorder_start_a")

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
                       "failureGate": None, "failureElapsedSeconds": None}
            original_rename = Path.rename

            def fail_second(path, destination):
                if path.name == "recording-b.webm":
                    raise OSError("synthetic rename failure")
                return original_rename(path, destination)

            with patch.object(Path, "rename", new=fail_second):
                with self.assertRaises(capture.GateError) as failure:
                    capture.export_host_recordings(
                        root, recordings, receipt, failure_elapsed_seconds=12.3)
            self.assertEqual(failure.exception.gate, "evidence_export")
            self.assertFalse(any((root/"evidence").glob("*.webm")))
            self.assertTrue(all(not path.exists() for path in recordings))
            failed = json.loads((root/"evidence"/"attempt.json").read_text())
            self.assertEqual(failed["result"], "NOT_COMPLETED")
            self.assertFalse(failed["recordingsExported"])
            self.assertEqual(failed["failureGate"], "evidence_export")
            self.assertEqual(failed["failureElapsedSeconds"], 12.3)

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
                       "failureGate": None, "failureElapsedSeconds": None}
            capture.export_host_recordings(root, recordings, receipt)
            self.assertEqual({path.name for path in (root/"evidence").iterdir()}, {
                "recording-a.webm", "recording-b.webm", "provenance.json",
                "attempt.json", "SHA256SUMS"})
            self.assertFalse((root/"evidence-ready").exists())
            self.assertFalse((root/"evidence-failure-receipts").exists())
            complete = json.loads((root/"evidence"/"attempt.json").read_text())
            self.assertTrue(complete["recordingsExported"])
            self.assertIsNone(complete["failureGate"])
            self.assertIsNone(complete["failureElapsedSeconds"])

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

    def test_recording_contract_captures_real_setup_before_interaction(self):
        self.assertEqual(capture.RECORDING_LIMIT_SECONDS, 180)
        self.assertEqual(capture.SCENARIO_BUDGET_SECONDS, 165)
        self.assertEqual(capture.PREFLIGHT_STEPS, ("boot_install_bluetooth_ready",))
        self.assertEqual(capture.SCENARIO_STEPS[:4], (
            "recording_commands_started", "actual_onboarding_permissions_completed",
            "foreground_services_active", "both_apps_display_real_peer"))
        main_source = Path(capture.__file__).read_text().split("def main(root):", 1)[1]
        scenario_source = Path(capture.__file__).read_text().split(
            "def run_product_scenario", 1)[1].split("def main(root):", 1)[0]
        self.assertLess(main_source.index("start_host_recorder(serial"),
                        main_source.index('event("recording_commands_started"'))
        self.assertLess(main_source.index('event("recording_commands_started"'),
                        main_source.index("run_product_scenario(deadline, event)"))
        self.assertNotIn("recorded_relaunch", main_source)
        self.assertIn("onboarding_deadline = min(deadline", scenario_source)
        self.assertIn("service_deadline = min(deadline", scenario_source)
        self.assertIn("peer_deadline = min(deadline", scenario_source)
        self.assertNotIn("recording_commands_started", scenario_source)

    def test_scenario_clock_has_explicit_zero_then_real_positive_onboarding(self):
        receipt = {"events": []}
        capture.append_scenario_event(
            receipt, "recording_commands_started", video_start=100.0, elapsed=0.0)
        with patch("capture.time.monotonic", return_value=148.3):
            capture.append_scenario_event(
                receipt, "actual_onboarding_permissions_completed", video_start=100.0)
        self.assertEqual(receipt["events"], [
            {"step": "recording_commands_started", "elapsedSeconds": 0.0},
            {"step": "actual_onboarding_permissions_completed", "elapsedSeconds": 48.3},
        ])

    def test_scenario_clock_rejects_fake_or_over_budget_product_events(self):
        receipt = {"events": []}
        capture.append_scenario_event(
            receipt, "recording_commands_started", video_start=100.0, elapsed=0.0)
        with self.assertRaises(ValueError):
            capture.append_scenario_event(
                receipt, "actual_onboarding_permissions_completed",
                video_start=100.0, elapsed=0.0)
        with patch("capture.time.monotonic", return_value=265.0):
            with self.assertRaises(capture.GateError) as failure:
                capture.append_scenario_event(
                    receipt, "actual_onboarding_permissions_completed", video_start=100.0)
        self.assertEqual(failure.exception.gate, "scenario_budget")

    def test_onboarding_uses_product_generated_nickname_and_taps_real_start(self):
        welcome = [
            E.Element("node", {"text": "Your nickname", "enabled": "true"}),
            E.Element("node", {
                "text": "Start", "enabled": "true", "clickable": "true",
                "bounds": "[0,0][20,20]",
            }),
        ]
        home = [E.Element("node", {"text": "Public room", "enabled": "true"})]
        with patch("capture.deadline_adb") as command, \
             patch("capture.wait_activity_resumed"), \
             patch("capture.nodes", side_effect=[welcome, home]), \
             patch("capture.tap") as tap, \
             patch("capture.bounded_sleep", return_value=True):
            capture.onboarding(capture.SERIALS[0], capture.time.monotonic()+2)

        self.assertEqual(command.call_count, 1)
        self.assertEqual(command.call_args.args[3:6], ("shell", "am", "start"))
        tap.assert_called_once_with(
            capture.SERIALS[0], welcome[1], "onboarding_welcome_start", command.call_args.args[2])
        onboarding_source = Path(capture.__file__).read_text().split(
            "def onboarding", 1)[1].split("def send", 1)[0]
        self.assertNotIn('"input"', onboarding_source)
        self.assertNotIn("EditText", onboarding_source)
        self.assertNotIn("keyboard", onboarding_source.casefold())

    def test_onboarding_welcome_start_failure_is_precise(self):
        welcome = [
            E.Element("node", {"text": "Your nickname", "enabled": "true"}),
            E.Element("node", {
                "text": "Start", "enabled": "true", "clickable": "true",
                "bounds": "[0,0][20,20]",
            }),
        ]
        failure = capture.GateError("onboarding_welcome_start", capture.SERIALS[0])
        with patch("capture.deadline_adb"), patch("capture.wait_activity_resumed"), \
             patch("capture.nodes", return_value=welcome), \
             patch("capture.tap", side_effect=failure):
            with self.assertRaises(capture.GateError) as raised:
                capture.onboarding(capture.SERIALS[0], capture.time.monotonic()+2)
        self.assertEqual(raised.exception.gate, "onboarding_welcome_start_a")

    def test_onboarding_welcome_without_enabled_start_has_timeout_gate(self):
        welcome = [
            E.Element("node", {"text": "Your nickname", "enabled": "true"}),
            E.Element("node", {"text": "Start", "enabled": "false"}),
        ]
        with patch("capture.deadline_adb"), patch("capture.wait_activity_resumed"), \
             patch("capture.nodes", return_value=welcome), \
             patch("capture.bounded_sleep", return_value=False):
            with self.assertRaises(capture.GateError) as raised:
                capture.onboarding(capture.SERIALS[1], capture.time.monotonic()+2)
        self.assertEqual(raised.exception.gate, "onboarding_welcome_timeout_b")

    def test_onboarding_preserves_last_phase_only_at_caller_deadline(self):
        clock = [0.0]
        observed = []

        def deadline_timeout(*_args, **_kwargs):
            clock[0] = 10.0
            kwargs = _kwargs
            kwargs["observer"]("ui_command", "a", 1, 0.1, 0.1)
            failure = capture.GateError("ui_dump_command_timeout", capture.SERIALS[0])
            failure.caller_deadline_reached = True
            raise failure

        with patch("capture.time.monotonic", side_effect=lambda: clock[0]), \
             patch("capture.deadline_adb"), patch("capture.wait_activity_resumed"), \
             patch("capture.nodes", side_effect=deadline_timeout):
            with self.assertRaises(capture.GateError) as raised:
                capture.onboarding(
                    capture.SERIALS[0], 10.0,
                    observer=lambda *values: observed.append(values),
                )
        self.assertEqual(raised.exception.gate, "onboarding_unknown_a")
        self.assertEqual([event[0] for event in observed], ["activity_resumed", "ui_command"])

        clock[0] = 0.0
        early = capture.GateError("ui_dump_command_timeout", capture.SERIALS[0])
        with patch("capture.time.monotonic", side_effect=lambda: clock[0]), \
             patch("capture.deadline_adb"), patch("capture.wait_activity_resumed"), \
             patch("capture.nodes", side_effect=early):
            with self.assertRaises(capture.GateError) as raised:
                capture.onboarding(capture.SERIALS[0], 10.0)
        self.assertEqual(raised.exception.gate, "ui_dump_command_timeout_a")

    def test_onboarding_does_not_remap_early_timeout_after_retry_sleep_crosses_deadline(self):
        clock = [0.0]

        def early_timeout(*_args, **_kwargs):
            clock[0] = 13.0
            raise capture.GateError("ui_dump_command_timeout", capture.SERIALS[0])

        def cross_deadline(_deadline, _seconds):
            clock[0] = 13.1
            return False

        with patch("capture.time.monotonic", side_effect=lambda: clock[0]), \
             patch("capture.deadline_adb"), patch("capture.wait_activity_resumed"), \
             patch("capture.ui_dump_attempt", side_effect=early_timeout), \
             patch("capture.bounded_sleep", side_effect=cross_deadline):
            with self.assertRaises(capture.GateError) as raised:
                capture.onboarding(capture.SERIALS[0], 13.1)
        self.assertEqual(raised.exception.gate, "ui_dump_command_timeout_a")
        self.assertFalse(raised.exception.caller_deadline_reached)

    def test_onboarding_observer_none_emits_no_trace(self):
        welcome = [
            E.Element("node", {"text": "Your nickname", "enabled": "true"}),
            E.Element("node", {
                "text": "Start", "enabled": "true", "clickable": "true",
                "bounds": "[0,0][20,20]",
            }),
        ]
        home = [E.Element("node", {"text": "Public room", "enabled": "true"})]
        with patch("capture.deadline_adb"), patch("capture.wait_activity_resumed"), \
             patch("capture.nodes", side_effect=[welcome, home]), patch("capture.tap"), \
             patch("capture.bounded_sleep", return_value=True), \
             patch("capture.emit_onboarding_trace") as emit:
            capture.onboarding(capture.SERIALS[0], capture.time.monotonic() + 2)
        emit.assert_not_called()

    def test_onboarding_rejects_premature_home_until_welcome_start_home(self):
        home = [E.Element("node", {"text": "Public room", "enabled": "true"})]
        welcome = [
            E.Element("node", {"text": "Your nickname", "enabled": "true"}),
            E.Element("node", {
                "text": "Start", "enabled": "true", "clickable": "true",
                "bounds": "[0,0][20,20]",
            }),
        ]
        with patch("capture.deadline_adb"), patch("capture.wait_activity_resumed"), \
             patch("capture.nodes", side_effect=[home, welcome, welcome, home]), \
             patch("capture.tap") as tap, \
             patch("capture.bounded_sleep", return_value=True):
            capture.onboarding(capture.SERIALS[0], capture.time.monotonic() + 2)
        tap.assert_called_once()

    def test_onboarding_home_without_start_never_passes(self):
        home = [E.Element("node", {"text": "Public room", "enabled": "true"})]
        welcome_without_start = [
            E.Element("node", {"text": "Your nickname", "enabled": "true"}),
        ]
        with patch("capture.deadline_adb"), patch("capture.wait_activity_resumed"), \
             patch("capture.nodes", side_effect=[home, welcome_without_start, home]), \
             patch("capture.tap") as tap, \
             patch("capture.bounded_sleep", side_effect=[True, True, False]):
            with self.assertRaises(capture.GateError) as raised:
                capture.onboarding(capture.SERIALS[0], capture.time.monotonic() + 2)
        self.assertEqual(raised.exception.gate, "onboarding_welcome_timeout_a")
        tap.assert_not_called()

    def test_onboarding_observer_receives_only_allowlisted_progress(self):
        welcome = [
            E.Element("node", {
                "text": "Your nickname", "enabled": "true",
                "package": capture.PACKAGE,
            }),
            E.Element("node", {
                "text": "Start", "enabled": "true", "clickable": "true",
                "bounds": "[0,0][20,20]", "package": capture.PACKAGE,
            }),
        ]
        home = [E.Element("node", {
            "text": "Public room", "enabled": "true", "package": capture.PACKAGE,
        })]
        observed = []

        with patch("capture.deadline_adb"), patch("capture.wait_activity_resumed"), \
             patch("capture.nodes", side_effect=[welcome, home]), \
             patch("capture.tap"), patch("capture.bounded_sleep", return_value=True):
            capture.onboarding(
                capture.SERIALS[0], capture.time.monotonic() + 2,
                observer=lambda *values: observed.append(values),
            )
        self.assertEqual([event[0] for event in observed], [
            "activity_resumed", "screen_welcome", "welcome_seen", "start_tapped",
            "screen_home_ready", "home",
        ])
        serialized = json.dumps(observed)
        for forbidden in ("Your nickname", "Public room", "<?xml", "/data/local/tmp",
                          "emulator-5554"):
            self.assertNotIn(forbidden, serialized)

    def test_onboarding_screen_classifier_uses_only_fixed_markers_and_packages(self):
        def app_node(text, enabled="true"):
            return E.Element("node", {
                "text": text, "enabled": enabled, "package": capture.PACKAGE,
            })

        cases = (
            ([app_node("Your nickname")], "screen_welcome"),
            ([app_node("Your nickname", "false")], "screen_welcome_marker_disabled"),
            ([app_node("Public room")], "screen_home_before_welcome"),
            ([app_node("Bluetooth, to find the phones around")],
             "screen_bluetooth_explainer"),
            ([app_node("Get told when a message arrives")],
             "screen_notifications_explainer"),
            ([E.Element("node", {"package": "com.google.android.permissioncontroller"})],
             "screen_permission_system"),
            ([app_node("Getting Wesh ready…")], "screen_startup_preparing"),
            ([app_node("Your identity is unavailable for now. "
                       "Try again without erasing the app’s data.")],
             "screen_startup_blocked"),
            ([app_node("Initializing mesh network")], "screen_app_loading"),
            ([app_node("Setup Not Complete")], "screen_app_error"),
            ([app_node("Bluetooth unavailable")], "screen_bluetooth_fallback"),
            ([app_node("unrecognized app screen")], "screen_unknown_app"),
            ([E.Element("node", {"package": "com.android.systemui"})],
             "screen_unknown_system"),
            ([E.Element("node", {
                "package": "android", "resource-id": "android:id/aerr_close",
            })], "screen_android_error"),
            ([E.Element("node", {"package": "synthetic.foreign"})],
             "screen_unknown_foreign"),
            ([app_node("unrecognized app screen"),
              E.Element("node", {"package": "com.android.systemui"})],
             "screen_unknown_mixed"),
        )
        for items, expected in cases:
            self.assertEqual(capture.onboarding_screen_event(items), expected)
            self.assertIn(expected, capture.ONBOARDING_TRACE_EVENTS)
        self.assertEqual(capture.onboarding_screen_event(
            [app_node("Public room")], welcome_seen=True, start_tapped=True,
        ), "screen_home_ready")

    def test_onboarding_deduplicates_screen_transitions_and_repeats_terminal_class(self):
        home = [E.Element("node", {
            "text": "Public room", "enabled": "true", "package": capture.PACKAGE,
        })]
        observed = []
        with patch("capture.deadline_adb"), patch("capture.wait_activity_resumed"), \
             patch("capture.nodes", return_value=home), \
             patch("capture.bounded_sleep", side_effect=[True, False]):
            with self.assertRaises(capture.GateError) as raised:
                capture.onboarding(
                    capture.SERIALS[0], capture.time.monotonic() + 2,
                    observer=lambda *values: observed.append(values),
                )
        self.assertEqual(raised.exception.gate, "onboarding_unknown_a")
        self.assertEqual([entry[0] for entry in observed], [
            "activity_resumed", "screen_home_before_welcome",
            "screen_home_before_welcome",
        ])

    def test_main_records_first_launch_setup_and_interaction_under_one_clock(self):
        clock = [0.0]
        calls = []
        scenario_deadlines = []
        exported = {}
        video_origin = []
        node = E.Element("node", {"enabled": "true", "bounds": "[0,0][2,2]"})

        def advance(seconds):
            clock[0] += seconds

        def boot(serial, _apk):
            calls.append(("boot", serial))
            advance(5)

        def capability(serial, _deadline):
            calls.append(("capability", serial))

        def start(serial, _path, deadline):
            if not video_origin:
                video_origin.append(clock[0])
            calls.append(("start", serial))
            scenario_deadlines.append(deadline)
            advance(1)

        def scenario(deadline, event, observer=None):
            self.assertIsNone(observer)
            calls.append(("scenario",))
            scenario_deadlines.append(deadline)
            for step in capture.SCENARIO_STEPS[1:]:
                advance(1)
                event(step)

        def export(_root, _recordings, receipt, **_kwargs):
            exported.update(json.loads(json.dumps(receipt)))

        capture.defer_ui_dump_cleanup(
            capture.SERIALS[0],
            "/data/local/tmp/wesh-fgs-window-a-0123456789abcdef-00000001.xml")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/"evidence").mkdir()
            with patch.dict(capture.os.environ, {
                    "GITHUB_ACTIONS": "true", "EVIDENCE_HEAD": "1"*40}, clear=False), \
                 ExitStack() as stack:
                stack.enter_context(patch("capture.ThreadPoolExecutor", ImmediateExecutor))
                stack.enter_context(patch("capture.time.monotonic", side_effect=lambda: clock[0]))
                stack.enter_context(patch("capture.boot_and_install", side_effect=boot))
                stack.enter_context(patch("capture.require_host_recorder", side_effect=capability))
                stack.enter_context(patch("capture.start_host_recorder", side_effect=start))
                stack.enter_context(patch("capture.run_product_scenario", side_effect=scenario))
                stack.enter_context(patch("capture.wait_host_recordings"))
                stack.enter_context(patch("capture.export_host_recordings", side_effect=export))
                stack.enter_context(patch("capture.adb", side_effect=OSError("private sweep spawn")))
                capture.main(root)

        starts = [index for index, call in enumerate(calls) if call[0] == "start"]
        scenarios = [index for index, call in enumerate(calls) if call[0] == "scenario"]
        self.assertEqual(len(starts), 2)
        self.assertEqual(len(scenarios), 1)
        self.assertLess(max(starts), min(scenarios))
        self.assertTrue(all(deadline <= video_origin[0] + capture.SCENARIO_BUDGET_SECONDS
                            for deadline in scenario_deadlines))
        self.assertEqual([event["step"] for event in exported["preflightEvents"]],
                         list(capture.PREFLIGHT_STEPS))
        self.assertEqual([event["step"] for event in exported["events"]],
                         list(capture.SCENARIO_STEPS))
        self.assertEqual(exported["deferredUiDumpCleanupCountBySide"], {"a": 1, "b": 0})
        self.assertIsNone(exported["failureElapsedSeconds"])
        self.assertNotIn("private", json.dumps(exported))
        times = [event["elapsedSeconds"] for event in exported["events"]]
        self.assertEqual(times[0], 0.0)
        self.assertTrue(all(0 < value < capture.SCENARIO_BUDGET_SECONDS
                            for value in times[1:]))
        self.assertTrue(all(previous < current for previous, current in zip(times, times[1:])))

    def test_main_second_recorder_failure_never_launches_or_exports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/"evidence").mkdir()
            start = Mock(side_effect=[
                None, capture.GateError("host_recorder_start", capture.SERIALS[1]),
            ])
            onboarding = Mock()
            export = Mock()
            wait_recordings = Mock()
            with patch.dict(capture.os.environ, {
                    "GITHUB_ACTIONS": "true", "EVIDENCE_HEAD": "1"*40}, clear=False), \
                 patch("capture.ThreadPoolExecutor", ImmediateExecutor), \
                 patch("capture.boot_and_install"), \
                 patch("capture.require_host_recorder"), \
                 patch("capture.start_host_recorder", start), \
                 patch("capture.onboarding", onboarding), \
                 patch("capture.wait_host_recordings", wait_recordings), \
                 patch("capture.export_host_recordings", export):
                with self.assertRaises(RuntimeError):
                    capture.main(root)
            attempt = json.loads((root/"evidence/attempt.json").read_text())
        self.assertEqual(start.call_count, 2)
        onboarding.assert_not_called()
        wait_recordings.assert_not_called()
        export.assert_not_called()
        self.assertEqual(attempt["events"], [])
        self.assertEqual(attempt["failureGate"], "host_recorder_start_b")
        self.assertIsInstance(attempt["failureElapsedSeconds"], float)
        self.assertGreaterEqual(attempt["failureElapsedSeconds"], 0)

    def test_real_first_public_disclosure_requires_confirmation(self):
        title = E.Element("node", {
            "text": "Send to the public room?", "enabled": "true",
            "package": capture.PACKAGE,
        })
        self.assertEqual(capture.public_send_state([title], "synthetic"), "confirm")

    def test_editor_and_hint_descendants_never_count_as_message_bubbles(self):
        field = E.Element("node", {
            "class": "androidx.compose.ui.EditText", "package": capture.PACKAGE,
        })
        hint = E.SubElement(field, "node", {
            "content-desc": "draft synthetic decoration", "package": capture.PACKAGE,
        })
        self.assertEqual(capture.public_send_state([field, hint], "synthetic"), "pending")

    def test_completed_public_send_requires_app_message_bubble_not_empty_draft(self):
        bubble = E.Element("node", {
            "content-desc": "You, fp, synthetic, now", "enabled": "true",
            "package": capture.PACKAGE,
        })
        field = E.Element("node", {
            "class": "android.widget.EditText", "text": "hint remains",
            "package": capture.PACKAGE,
        })
        self.assertEqual(capture.public_send_state([bubble, field], "synthetic"), "submitted")

    def test_message_bubble_rejects_substring_and_ancestor_containing_editor(self):
        substring = E.Element("node", {
            "content-desc": "You, fp, synthetic-extra, now", "package": capture.PACKAGE,
        })
        self.assertIsNone(capture.message_bubble([substring], "synthetic"))

        ancestor = E.Element("node", {
            "content-desc": "You, fp, synthetic, now", "package": capture.PACKAGE,
        })
        editor = E.SubElement(ancestor, "node", {
            "class": "androidx.appcompat.widget.AppCompatEditText",
            "content-desc": "synthetic", "package": capture.PACKAGE,
        })
        child = E.SubElement(editor, "node", {
            "content-desc": "synthetic", "package": capture.PACKAGE,
        })
        self.assertIsNone(capture.message_bubble([ancestor, editor, child], "synthetic"))
        self.assertTrue(capture.is_editor(editor))

        editable = E.Element("node", {
            "editable": "true", "content-desc": "synthetic", "package": capture.PACKAGE,
        })
        self.assertIsNone(capture.message_bubble([editable], "synthetic"))

    def test_system_notification_and_dialog_text_never_count_as_message_bubbles(self):
        notification = E.Element("node", {
            "content-desc": "synthetic", "package": "com.android.systemui",
        })
        self.assertIsNone(capture.message_bubble([notification], "synthetic"))

        dialog = E.Element("node", {"package": capture.PACKAGE})
        marker = E.SubElement(dialog, "node", {
            "text": "Send to the public room?", "enabled": "true",
            "package": capture.PACKAGE,
        })
        message = E.SubElement(dialog, "node", {
            "content-desc": "synthetic", "package": capture.PACKAGE,
        })
        self.assertIsNone(capture.message_bubble([dialog, marker, message], "synthetic"))

    def test_send_feedback_classification_is_fixed_and_never_exports_text(self):
        for text, expected in capture.SEND_FEEDBACK_EVENTS.items():
            item = E.Element("node", {"text": text, "package": capture.PACKAGE})
            self.assertEqual(capture.send_feedback_event([item]), expected)
            self.assertIn(expected, capture.ONBOARDING_TRACE_EVENTS)
            self.assertNotIn(text, expected)
        arbitrary = E.Element("node", {
            "text": "private runtime text", "package": capture.PACKAGE,
        })
        foreign = E.Element("node", {
            "text": next(iter(capture.SEND_FEEDBACK_EVENTS)),
            "package": "com.android.systemui",
        })
        self.assertIsNone(capture.send_feedback_event([arbitrary, foreign]))

    def test_confirmation_action_is_scoped_to_dialog_without_editor(self):
        root = E.Element("node", {"package": capture.PACKAGE})
        editor = E.SubElement(root, "node", {
            "class": "android.widget.EditText", "package": capture.PACKAGE,
        })
        background = E.SubElement(root, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "package": capture.PACKAGE,
        })
        dialog = E.SubElement(root, "node", {"package": capture.PACKAGE})
        marker = E.SubElement(dialog, "node", {
            "text": "Send to the public room?", "enabled": "true",
            "package": capture.PACKAGE,
        })
        E.SubElement(dialog, "node", {
            "text": "Anyone around can read, relay and keep your message. "
                    "A nickname does not prove an identity.",
            "enabled": "true", "package": capture.PACKAGE,
        })
        E.SubElement(dialog, "node", {
            "text": "Cancel", "enabled": "true", "clickable": "true",
            "package": capture.PACKAGE,
        })
        confirmation = E.SubElement(dialog, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "package": capture.PACKAGE,
        })
        items = list(root.iter("node"))
        self.assertIs(capture.confirmation_action(items), confirmation)
        duplicate = E.SubElement(dialog, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "package": capture.PACKAGE,
        })
        self.assertIsNone(capture.confirmation_action(list(root.iter("node"))))
        dialog.remove(duplicate)
        dialog.remove(confirmation)
        items = list(root.iter("node"))
        self.assertIsNone(capture.confirmation_action(items))

        root.remove(editor)
        exterior = E.SubElement(root, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "package": capture.PACKAGE,
        })
        self.assertIsNone(capture.confirmation_action(list(root.iter("node"))))

    def test_send_uses_actionable_controls_scoped_confirmation_and_real_bubble(self):
        control = E.Element("node", {
            "enabled": "true", "clickable": "true", "bounds": "[0,0][20,20]",
            "package": capture.PACKAGE,
        })
        composer = E.Element("node", {"package": capture.PACKAGE})
        E.SubElement(composer, "node", {
            "class": "android.widget.EditText", "editable": "true",
            "text": "synthetic", "package": capture.PACKAGE,
        })
        submit = E.SubElement(composer, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "bounds": "[0,0][20,20]", "package": capture.PACKAGE,
        })
        dialog = E.Element("node", {"package": capture.PACKAGE})
        marker = E.SubElement(dialog, "node", {
            "text": "Send to the public room?", "enabled": "true",
            "package": capture.PACKAGE,
        })
        E.SubElement(dialog, "node", {
            "text": "Anyone around can read, relay and keep your message. "
                    "A nickname does not prove an identity.",
            "enabled": "true", "package": capture.PACKAGE,
        })
        E.SubElement(dialog, "node", {
            "text": "Cancel", "enabled": "true", "clickable": "true",
            "package": capture.PACKAGE,
        })
        confirmation = E.SubElement(dialog, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "bounds": "[0,0][20,20]", "package": capture.PACKAGE,
        })
        bubble = E.Element("node", {
            "content-desc": "You, fp, synthetic, now", "package": capture.PACKAGE,
        })
        observed = []
        with patch("capture.wait_action", return_value=control) as action, \
             patch("capture.deadline_adb"), \
             patch("capture.nodes", side_effect=[
                 list(composer.iter("node")), list(dialog.iter("node")), [bubble],
             ]), \
             patch("capture.tap") as tap, \
             patch("capture.bounded_sleep", return_value=True), \
             patch("capture.time.monotonic", return_value=1.0):
            capture.send(
                capture.SERIALS[0], "synthetic", 10.0,
                observer=lambda *values: observed.append(values), trace_started=0.0,
            )
        self.assertEqual(action.call_count, 1)
        self.assertEqual([call.args[2] for call in tap.call_args_list], [
            "message_composer", "message_submit", "message_confirmation",
        ])
        self.assertIs(tap.call_args_list[1].args[1], submit)
        self.assertIs(tap.call_args_list[-1].args[1], confirmation)
        self.assertEqual([entry[0] for entry in observed], [
            "composer_expected", "bubble_candidate_editor_only", "send_requested",
            "composer_ambiguous", "bubble_candidate_absent", "confirmation_visible",
            "confirmation_tap_requested", "bubble_candidate_exact",
            "confirmation_dismissed", "message_bubble",
        ])

    def test_send_requires_exact_draft_in_one_canonical_editor_before_submit(self):
        focus = E.Element("node", {
            "enabled": "true", "clickable": "true", "bounds": "[0,0][20,20]",
            "package": capture.PACKAGE,
        })
        root = E.Element("node", {"package": capture.PACKAGE})
        E.SubElement(root, "node", {
            "class": "android.widget.EditText", "editable": "true",
            "text": "different", "package": capture.PACKAGE,
        })
        E.SubElement(root, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "bounds": "[0,0][20,20]", "package": capture.PACKAGE,
        })
        observed = []
        with patch("capture.wait_action", return_value=focus), \
             patch("capture.deadline_adb"), \
             patch("capture.nodes", return_value=list(root.iter("node"))) as snapshot, \
             patch("capture.tap") as tap, \
             patch("capture.bounded_sleep", return_value=False), \
             patch("capture.time.monotonic", return_value=1.0):
            with self.assertRaises(capture.GateError) as failure:
                capture.send(
                    capture.SERIALS[0], "synthetic", 10.0,
                    observer=lambda *values: observed.append(values), trace_started=0.0,
                )
        self.assertEqual(failure.exception.gate, "message_composer_a")
        self.assertEqual(tap.call_count, 1)
        self.assertIsNotNone(snapshot.call_args.kwargs["observer"])
        self.assertEqual([entry[0] for entry in observed], [
            "composer_other", "bubble_candidate_absent",
        ])

    def test_send_never_uses_foreign_submit_action_with_exact_app_draft(self):
        focus = E.Element("node", {
            "enabled": "true", "clickable": "true", "bounds": "[0,0][20,20]",
            "package": capture.PACKAGE,
        })
        root = E.Element("node", {"package": capture.PACKAGE})
        E.SubElement(root, "node", {
            "class": "android.widget.EditText", "editable": "true",
            "text": "synthetic", "package": capture.PACKAGE,
        })
        E.SubElement(root, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "bounds": "[0,0][20,20]", "package": "com.android.systemui",
        })
        with patch("capture.wait_action", return_value=focus), \
             patch("capture.deadline_adb"), \
             patch("capture.nodes", return_value=list(root.iter("node"))), \
             patch("capture.tap") as tap, \
             patch("capture.bounded_sleep", return_value=False), \
             patch("capture.time.monotonic", return_value=1.0):
            with self.assertRaises(capture.GateError) as failure:
                capture.send(capture.SERIALS[0], "synthetic", 10.0)
        self.assertEqual(failure.exception.gate, "message_submit_a")
        self.assertEqual(tap.call_count, 1)

    def test_send_observes_stale_feedback_without_blocking_exact_bubble(self):
        focus = E.Element("node", {
            "enabled": "true", "clickable": "true", "bounds": "[0,0][20,20]",
            "package": capture.PACKAGE,
        })
        composer = E.Element("node", {"package": capture.PACKAGE})
        E.SubElement(composer, "node", {
            "class": "android.widget.EditText", "editable": "true",
            "text": "synthetic", "package": capture.PACKAGE,
        })
        E.SubElement(composer, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "bounds": "[0,0][20,20]", "package": capture.PACKAGE,
        })
        E.SubElement(composer, "node", {
            "text": "Local sending interrupted or unavailable. Your text is kept.",
            "package": capture.PACKAGE,
        })
        bubble = E.Element("node", {
            "content-desc": "You, fp, synthetic, now", "package": capture.PACKAGE,
        })
        observed = []
        with patch("capture.wait_action", return_value=focus), \
             patch("capture.deadline_adb"), \
             patch("capture.nodes", side_effect=[list(composer.iter("node")), [bubble]]), \
             patch("capture.tap"), \
             patch("capture.bounded_sleep", return_value=True), \
             patch("capture.time.monotonic", return_value=1.0):
            capture.send(
                capture.SERIALS[0], "synthetic", 10.0,
                observer=lambda *values: observed.append(values), trace_started=0.0,
            )
        self.assertIn("feedback_send_unavailable", [entry[0] for entry in observed])
        self.assertEqual(observed[-1][0], "message_bubble")

    def test_send_fails_closed_on_new_allowlisted_feedback_after_request(self):
        focus = E.Element("node", {
            "enabled": "true", "clickable": "true", "bounds": "[0,0][20,20]",
            "package": capture.PACKAGE,
        })
        composer = E.Element("node", {"package": capture.PACKAGE})
        E.SubElement(composer, "node", {
            "class": "android.widget.EditText", "editable": "true",
            "text": "synthetic", "package": capture.PACKAGE,
        })
        E.SubElement(composer, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "bounds": "[0,0][20,20]", "package": capture.PACKAGE,
        })
        rejected = E.Element("node", {"package": capture.PACKAGE})
        E.SubElement(rejected, "node", {
            "text": "The destination changed. Your text is kept.",
            "package": capture.PACKAGE,
        })
        observed = []
        with patch("capture.wait_action", return_value=focus), \
             patch("capture.deadline_adb"), \
             patch("capture.nodes", side_effect=[
                 list(composer.iter("node")), list(rejected.iter("node")),
             ]), \
             patch("capture.tap"), \
             patch("capture.time.monotonic", return_value=1.0):
            with self.assertRaises(capture.GateError) as failure:
                capture.send(
                    capture.SERIALS[0], "synthetic", 10.0,
                    observer=lambda *values: observed.append(values), trace_started=0.0,
                )
        self.assertEqual(failure.exception.gate, "message_submit_a")
        self.assertIn("feedback_destination_changed", [entry[0] for entry in observed])

    def test_send_never_retaps_persistent_confirmation_and_traces_caller_deadline(self):
        focus = E.Element("node", {
            "enabled": "true", "clickable": "true", "bounds": "[0,0][20,20]",
            "package": capture.PACKAGE,
        })
        composer = E.Element("node", {"package": capture.PACKAGE})
        E.SubElement(composer, "node", {
            "class": "android.widget.EditText", "editable": "true",
            "text": "synthetic", "package": capture.PACKAGE,
        })
        E.SubElement(composer, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "bounds": "[0,0][20,20]", "package": capture.PACKAGE,
        })
        dialog = E.Element("node", {"package": capture.PACKAGE})
        E.SubElement(dialog, "node", {
            "text": "Send to the public room?", "enabled": "true",
            "package": capture.PACKAGE,
        })
        E.SubElement(dialog, "node", {
            "text": "Anyone around can read, relay and keep your message. "
                    "A nickname does not prove an identity.",
            "enabled": "true", "package": capture.PACKAGE,
        })
        E.SubElement(dialog, "node", {
            "text": "Cancel", "enabled": "true", "clickable": "true",
            "package": capture.PACKAGE,
        })
        E.SubElement(dialog, "node", {
            "text": "Send in public", "enabled": "true", "clickable": "true",
            "bounds": "[0,0][20,20]", "package": capture.PACKAGE,
        })
        timeout = capture.GateError("ui_dump_command_timeout", capture.SERIALS[0])
        timeout.caller_deadline_reached = True
        observed = []
        with patch("capture.wait_action", return_value=focus), \
             patch("capture.deadline_adb"), \
             patch("capture.nodes", side_effect=[
                 list(composer.iter("node")), list(dialog.iter("node")),
                 list(dialog.iter("node")), timeout,
             ]), \
             patch("capture.tap") as tap, \
             patch("capture.bounded_sleep", return_value=True), \
             patch("capture.time.monotonic", return_value=1.0):
            with self.assertRaises(capture.GateError) as failure:
                capture.send(
                    capture.SERIALS[0], "synthetic", 10.0,
                    observer=lambda *values: observed.append(values), trace_started=0.0,
                )
        self.assertIs(failure.exception, timeout)
        self.assertEqual([call.args[2] for call in tap.call_args_list], [
            "message_composer", "message_submit", "message_confirmation",
        ])
        self.assertEqual([entry[0] for entry in observed].count(
            "confirmation_present_after_tap"), 2)
        self.assertEqual([entry[0] for entry in observed].count(
            "confirmation_tap_requested"), 1)
        self.assertEqual(observed[-1][0], "failure_caller_deadline_reached")

    def test_remote_wait_observes_closed_bubble_candidates_and_preserves_early_ui_gate(self):
        editor = E.Element("node", {
            "class": "android.widget.EditText", "editable": "true",
            "text": "synthetic", "package": capture.PACKAGE,
        })
        early = capture.GateError("ui_dump_command_timeout", capture.SERIALS[1])
        early.caller_deadline_reached = False
        observed = []
        with patch("capture.nodes", side_effect=[[editor], early]) as snapshot, \
             patch("capture.bounded_sleep", return_value=True), \
             patch("capture.time.monotonic", return_value=1.0):
            with self.assertRaises(capture.GateError) as failure:
                capture.wait_message_bubble(
                    capture.SERIALS[1], "synthetic", 10.0,
                    observer=lambda *values: observed.append(values),
                    trace_started=0.0, remote=True,
                )
        self.assertIs(failure.exception, early)
        self.assertIsNotNone(snapshot.call_args_list[0].kwargs["observer"])
        self.assertIn("bubble_candidate_editor_only", [entry[0] for entry in observed])
        self.assertNotIn("failure_caller_deadline_reached", [entry[0] for entry in observed])

    def test_shared_product_scenario_requires_foreground_and_remote_bubbles(self):
        events = []
        observed = []
        target = E.Element("node", {
            "enabled": "true", "clickable": "true", "bounds": "[0,0][20,20]",
        })
        with patch("capture.ThreadPoolExecutor", ImmediateExecutor), \
             patch("capture.onboarding") as onboarding, \
             patch("capture.wait_fgs_active") as fgs, \
             patch("capture.wait_ui", return_value=target) as wait_ui, \
             patch("capture.wait_action", return_value=target) as wait_action, \
             patch("capture.tap"), patch("capture.send") as send, \
             patch("capture.wait_activity_resumed") as resumed, \
             patch("capture.wait_message_bubble") as remote, \
             patch("capture.deadline_adb"), \
             patch("capture.quit_via_notification"), \
             patch("capture.time.monotonic", return_value=1.0):
            capture.run_product_scenario(
                100.0, events.append,
                observer=lambda *values: observed.append(values),
            )
        self.assertEqual(onboarding.call_count, 2)
        self.assertEqual(fgs.call_count, 3)
        self.assertEqual([call.args[1] for call in wait_ui.call_args_list], [
            "1 phone in range", "1 phone in range", "Mesh running",
        ])
        self.assertEqual(wait_action.call_count, 3)
        self.assertEqual(send.call_count, 3)
        self.assertEqual(resumed.call_count, 3)
        self.assertEqual(remote.call_count, 3)
        self.assertTrue(all(call.kwargs["remote"] for call in remote.call_args_list))
        self.assertEqual(events, list(capture.SCENARIO_STEPS[1:]))
        self.assertIn("room_open_tapped", [entry[0] for entry in observed])
        self.assertIn("activity_resumed_after_notification", [entry[0] for entry in observed])
        self.assertIn("quit_completed", [entry[0] for entry in observed])

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
                   "environment": "Two API35 x86_64 AVDs; netsim simulated BLE",
                   "emulator": "37.2.12", "systemImageRevision": 9,
                   "recordingsExported": True, "finalVideoQualified": False,
                   "notPhysicalEvidence": True, "notPlaySigned": True, "failureGate": None,
                   "failureElapsedSeconds": None,
                   "deferredUiDumpCleanupCountBySide": {"a": 0, "b": 0},
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
            self.assertEqual(last, len(capture.SCENARIO_STEPS))
            self.assertEqual(set(checksums), localassemble.HASHED_INPUTS)
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.validate_inputs(root, "2"*40)

    def test_local_metadata_gate_rejects_modified_recording(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.local_bundle(directory)
            (root/"recording-a.webm").write_bytes(b"changed unit bytes")
            with self.assertRaises(localassemble.ValidationFailure):
                localassemble.validate_inputs(root, "1"*40)

    def test_local_metadata_gate_requires_null_failure_clock_for_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.local_bundle(directory)
            attempt = json.loads((root/"attempt.json").read_text())
            attempt["failureElapsedSeconds"] = 68.6
            (root/"attempt.json").write_text(json.dumps(attempt))
            self.rehash_bundle(root)
            with self.assertRaisesRegex(localassemble.ValidationFailure, "scenario_receipt"):
                localassemble.validate_inputs(root, "1"*40)

    def test_local_metadata_gate_rejects_missing_or_wrong_preflight(self):
        for mutation in ("missing", "wrong"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = self.local_bundle(directory)
                attempt = json.loads((root/"attempt.json").read_text())
                if mutation == "missing":
                    attempt["preflightEvents"].pop()
                else:
                    attempt["preflightEvents"][0]["step"] = "not_the_boot_gate"
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
            self.assertEqual(last, len(capture.SCENARIO_STEPS))

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
