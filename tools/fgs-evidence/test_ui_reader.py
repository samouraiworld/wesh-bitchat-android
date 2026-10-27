"""Static and synthetic checks for the tools-only one-shot UI reader."""

import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import types
import unittest
from unittest import mock
import xml.etree.ElementTree as E

import capture


HERE = Path(__file__).resolve().parent
SOURCE = HERE / "ui-reader/UiHierarchyReader.java"


class UiReaderTests(unittest.TestCase):
    def setUp(self):
        with capture._UI_READER_LOCK:
            capture._UI_READER_PATHS.clear()
            capture._UI_READER_READY.clear()

    def test_source_uses_public_reflection_direct_root_and_real_xml_validation(self):
        source = SOURCE.read_text()
        self.assertIn("com.android.uiautomator.core.UiAutomationShellWrapper", source)
        self.assertIn('getMethod("connect")', source)
        self.assertIn('"setCompressedLayoutHierarchy", boolean.class', source)
        self.assertIn('getMethod("getUiAutomation")', source)
        self.assertIn('getMethod("disconnect")', source)
        self.assertIn("getRootInActiveWindow()", source)
        self.assertIn("XmlPullParser.END_DOCUMENT", source)
        self.assertIn('"hierarchy".equals(parser.getName())', source)
        self.assertIn('"node".equals(parser.getName())', source)
        self.assertNotIn("waitForIdle", source)
        self.assertNotIn("setAccessible", source)
        self.assertNotIn("screenshot", source.casefold())
        self.assertEqual(source.count("System.exit("), 1)
        self.assertLess(source.index("int result = run(args)"), source.index("System.exit(result)"))
        self.assertIn("finally", source[source.index("static int run"):])

    def test_source_compiles_against_only_public_android_stubs(self):
        javac = shutil.which("javac")
        if javac is None:
            self.skipTest("javac unavailable")
        stubs = {
            "android/app/UiAutomation.java": """
                package android.app;
                import android.view.accessibility.AccessibilityNodeInfo;
                public class UiAutomation {
                    public AccessibilityNodeInfo getRootInActiveWindow() { return null; }
                }
            """,
            "android/graphics/Rect.java": """
                package android.graphics;
                public class Rect { public int right; public int bottom; }
            """,
            "android/view/accessibility/AccessibilityNodeInfo.java": """
                package android.view.accessibility;
                import android.graphics.Rect;
                public class AccessibilityNodeInfo {
                    public void getBoundsInScreen(Rect out) {}
                    public int getChildCount() { return 0; }
                    public AccessibilityNodeInfo getChild(int index) { return null; }
                    public void recycle() {}
                }
            """,
            "android/util/Xml.java": """
                package android.util;
                import org.xmlpull.v1.XmlPullParser;
                public class Xml { public static XmlPullParser newPullParser() { return null; } }
            """,
            "org/xmlpull/v1/XmlPullParser.java": """
                package org.xmlpull.v1;
                import java.io.InputStream;
                public interface XmlPullParser {
                    int END_DOCUMENT = 1; int START_TAG = 2;
                    void setInput(InputStream stream, String encoding) throws XmlPullParserException;
                    int next() throws XmlPullParserException, java.io.IOException;
                    String getName(); int getDepth();
                }
            """,
            "org/xmlpull/v1/XmlPullParserException.java": """
                package org.xmlpull.v1;
                public class XmlPullParserException extends Exception {}
            """,
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = [SOURCE]
            for relative, content in stubs.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(textwrap.dedent(content))
                files.append(path)
            result = subprocess.run(
                [javac, "-source", "8", "-target", "8", "-d", str(root / "classes"),
                 *map(str, files)], capture_output=True, timeout=30,
            )
        self.assertEqual(result.returncode, 0, "ui reader source compilation failed")

    def test_configure_checksums_owned_reader_and_cleanup_proves_absence(self):
        serial = capture.SERIALS[0]
        with tempfile.TemporaryDirectory() as directory:
            reader = Path(directory) / capture.UI_READER_HOST_JAR
            reader.write_bytes(b"synthetic reader")
            expected = hashlib.sha256(reader.read_bytes()).hexdigest()

            def command(_serial, phase, _deadline, *args, cap):
                self.assertEqual(_serial, serial)
                if args[0:2] == ("shell", "sha256sum"):
                    return f"{expected}  {args[-1]}\n"
                return ""

            with mock.patch("capture.deadline_adb", side_effect=command) as adb:
                capture.configure_ui_reader(serial, reader, capture.time.monotonic() + 20)
            self.assertEqual(adb.call_count, 5)
            guest = capture._UI_READER_PATHS[serial]
            self.assertTrue(capture.owned_ui_reader_path(serial, guest))
            self.assertIn(serial, capture._UI_READER_READY)

            with mock.patch("capture.deadline_adb", return_value="") as adb:
                capture.cleanup_ui_reader(serial, capture.time.monotonic() + 10)
            self.assertEqual(adb.call_count, 2)
            self.assertNotIn(serial, capture._UI_READER_PATHS)
            self.assertNotIn(serial, capture._UI_READER_READY)

    def test_configure_hash_mismatch_fails_closed_and_never_enables_reader(self):
        serial = capture.SERIALS[0]
        with tempfile.TemporaryDirectory() as directory:
            reader = Path(directory) / capture.UI_READER_HOST_JAR
            reader.write_bytes(b"synthetic reader")

            def command(_serial, _phase, _deadline, *args, cap):
                if args[0:2] == ("shell", "sha256sum"):
                    return f"{'0' * 64}  {args[-1]}\n"
                return ""

            with mock.patch("capture.deadline_adb", side_effect=command), \
                    mock.patch("capture.adb", return_value="") as cleanup:
                with self.assertRaises(capture.GateError) as failure:
                    capture.configure_ui_reader(
                        serial, reader, capture.time.monotonic() + 20)
            self.assertEqual(failure.exception.gate, "ui_reader_hash_a")
            self.assertIn(serial, capture._UI_READER_PATHS)
            self.assertNotIn(serial, capture._UI_READER_READY)
            cleanup.assert_called_once()
            with mock.patch("capture.deadline_adb", return_value=""):
                capture.cleanup_ui_reader(serial, capture.time.monotonic() + 10)
            self.assertNotIn(serial, capture._UI_READER_PATHS)

    def test_reader_command_uses_exact_app_process_and_closed_status_mapping(self):
        serial = capture.SERIALS[1]
        guest = capture.ui_reader_guest_path(serial)
        path = capture.ui_dump_path(serial)
        with capture._UI_READER_LOCK:
            capture._UI_READER_PATHS[serial] = guest
            capture._UI_READER_READY.add(serial)
        success = types.SimpleNamespace(
            returncode=0, stdout=b"WESH_UI_READER_OK\n", stderr=b"")
        with mock.patch("capture.subprocess.run", return_value=success) as run:
            capture.ui_reader_command(serial, path, capture.time.monotonic() + 20)
        argv = run.call_args.args[0]
        self.assertEqual(argv[:5], ["adb", "-s", serial, "shell", "env"])
        self.assertEqual(argv[-4:], ["app_process", "/system/bin",
                                    capture.UI_READER_CLASS, path])
        self.assertEqual(
            argv[5], f"CLASSPATH={guest}:/system/framework/uiautomator.jar")

        for status, phase in ((21, "ui_reader_bridge_b"), (23, "ui_reader_root_null_b"),
                              (24, "ui_reader_dump_b"), (25, "ui_reader_output_b"),
                              (26, "ui_reader_disconnect_b"),
                              (127, "ui_dump_command_error_b")):
            failed = types.SimpleNamespace(returncode=status, stdout=b"", stderr=b"private")
            with self.subTest(status=status), \
                    mock.patch("capture.subprocess.run", return_value=failed):
                with self.assertRaises(capture.GateError) as error:
                    capture.ui_reader_command(
                        serial, path, capture.time.monotonic() + 20)
                self.assertEqual(error.exception.gate, phase)
                self.assertNotIn("private", str(error.exception))

    def test_configured_snapshot_never_falls_back_to_classic_dump(self):
        serial = capture.SERIALS[0]
        with capture._UI_READER_LOCK:
            capture._UI_READER_PATHS[serial] = capture.ui_reader_guest_path(serial)
            capture._UI_READER_READY.add(serial)
        xml = '<hierarchy><node text="synthetic" /></hierarchy>'

        def command(_serial, *args, timeout):
            if args[0:2] == ("exec-out", "cat"):
                return xml
            self.assertNotEqual(args[0:3], ("shell", "uiautomator", "dump"))
            return ""

        with mock.patch("capture.ui_reader_command") as reader, \
                mock.patch("capture.adb", side_effect=command):
            items = capture.nodes(serial, capture.time.monotonic() + 20)
        reader.assert_called_once()
        self.assertEqual(len(items), 1)

    def test_build_script_pins_public_sdk_inputs_and_has_no_dependency_fetch(self):
        script = (HERE / "build-ui-reader.sh").read_text()
        self.assertIn("platforms/android-35/android.jar", script)
        self.assertIn("build-tools/37.0.0/d8", script)
        self.assertIn("--min-api 35", script)
        self.assertNotIn("maven", script.casefold())
        self.assertNotIn("gradle", script.casefold())
        self.assertNotIn("curl", script.casefold())
        self.assertNotIn("wget", script.casefold())


if __name__ == "__main__":
    unittest.main()
