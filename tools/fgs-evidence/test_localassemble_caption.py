#!/usr/bin/env python3
"""Focused unit checks for the local, non-product footer composition."""
import builtins
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import localassemble
from prepare import digest


ROOT = Path(__file__).resolve().parents[2]
FONT = ROOT / "app/src/main/res/font/geist_mono_bold.ttf"
PIL_AVAILABLE = importlib.util.find_spec("PIL") is not None


class LocalAssembleCaptionTests(unittest.TestCase):
    def test_scenario_clock_starts_at_zero_then_advances_strictly(self):
        expected = ("recording_commands_started", "real_onboarding", "foreground_service")

        def receipt(times):
            return {"events": [
                {"step": step, "elapsedSeconds": elapsed}
                for step, elapsed in zip(expected, times)
            ]}

        self.assertEqual(localassemble.event_times(
            receipt((0.0, 0.1, 0.2)), "events", expected, "sequence", "clock",
            starts_at_zero=True, strictly_increasing=True), [0.0, 0.1, 0.2])
        for invalid in ((0.1, 0.2, 0.3), (0.0, 0.0, 0.2), (0.0, 0.2, 0.2)):
            with self.subTest(invalid=invalid), \
                    self.assertRaisesRegex(localassemble.ValidationFailure, "clock"):
                localassemble.event_times(
                    receipt(invalid), "events", expected, "sequence", "clock",
                    starts_at_zero=True, strictly_increasing=True)

    def test_cleanup_diagnostics_allow_only_nonnegative_integer_side_counts(self):
        self.assertEqual(localassemble.validate_cleanup_diagnostics({
            "deferredUiDumpCleanupCountBySide": {"a": 0, "b": 3},
        }), {"a": 0, "b": 3})

        invalid = (
            {"a": 0},
            {"a": 0, "b": 0, "path": "/synthetic/private"},
            {"a": -1, "b": 0},
            {"a": False, "b": 0},
            {"a": 0.5, "b": 0},
        )
        for counters in invalid:
            with self.subTest(counters=counters), \
                    self.assertRaisesRegex(localassemble.ValidationFailure, "cleanup_diagnostics"):
                localassemble.validate_cleanup_diagnostics({
                    "deferredUiDumpCleanupCountBySide": counters,
                })

    def test_attempt_receipt_rejects_every_unallowlisted_top_level_field(self):
        attempt = {field: None for field in localassemble.ATTEMPT_FIELDS}
        attempt["deferredUiDumpCleanupCountBySide"] = {"a": 0, "b": 0}
        localassemble.validate_attempt_shape(attempt)

        for field in ("rawUiDump", "cleanupPath", "runnerDiagnostic"):
            contaminated = dict(attempt)
            contaminated[field] = "synthetic private detail"
            with self.subTest(field=field), \
                    self.assertRaisesRegex(localassemble.ValidationFailure,
                                                "scenario_receipt_fields"):
                localassemble.validate_attempt_shape(contaminated)

    @unittest.skipUnless(PIL_AVAILABLE, "Pillow is an explicit local-assembly dependency")
    def test_footer_png_is_deterministic_and_confined_to_footer_dimensions(self):
        from PIL import Image

        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "first.png"
            second = Path(directory) / "second.png"
            localassemble.render_footer(first, FONT)
            localassemble.render_footer(second, FONT)

            self.assertEqual(digest(first), digest(second))
            with Image.open(first) as image:
                self.assertEqual(image.mode, "RGB")
                self.assertEqual(image.size, (localassemble.OUTPUT_WIDTH,
                                              localassemble.FOOTER_HEIGHT))
                self.assertEqual(image.getpixel((0, 0)), (0, 0, 0))
                self.assertIsNotNone(image.getbbox())

    def test_composition_uses_png_overlay_below_untouched_screen_band(self):
        graph = localassemble.composition_filter()
        self.assertNotIn("drawtext", graph)
        self.assertIn("[2:v]format=rgba[footer]", graph)
        self.assertEqual(graph.count("force_original_aspect_ratio=decrease"), 2)
        self.assertEqual(
            graph.count(f"pad=720:{localassemble.SCREEN_HEIGHT}:(ow-iw)/2:(oh-ih)/2:black"), 2)
        self.assertIn(f"pad={localassemble.OUTPUT_WIDTH}:{localassemble.OUTPUT_HEIGHT}:0:0:black",
                      graph)
        self.assertIn(
            f"overlay=0:{localassemble.SCREEN_HEIGHT}:eof_action=repeat:shortest=1[out]", graph)

    @unittest.skipUnless(PIL_AVAILABLE, "Pillow is an explicit local-assembly dependency")
    def test_invalid_font_fails_before_creating_a_footer(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "footer.png"
            invalid = Path(directory) / "not-a-font.ttf"
            invalid.write_bytes(b"synthetic invalid font bytes")
            with self.assertRaisesRegex(localassemble.ValidationFailure, "font_not_supported"):
                localassemble.render_footer(target, invalid)
            self.assertFalse(target.exists())

    def test_missing_pillow_is_a_named_local_gate_not_an_import_failure(self):
        real_import = builtins.__import__

        def without_pillow(name, *args, **kwargs):
            if name == "PIL":
                raise ImportError("synthetic missing local dependency")
            return real_import(name, *args, **kwargs)

        with tempfile.TemporaryDirectory() as directory, \
                patch("builtins.__import__", side_effect=without_pillow):
            with self.assertRaisesRegex(localassemble.ValidationFailure, "local_pillow_unavailable"):
                localassemble.render_footer(Path(directory) / "footer.png", FONT)

    def test_probe_requires_single_h264_stream_and_expected_output_format(self):
        valid = json.dumps({
            "streams": [{"codec_type": "video", "codec_name": "h264", "width": 1440,
                         "height": 1360, "pix_fmt": "yuv420p", "nb_read_frames": "5400"}],
            "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "180.0"},
        })
        with patch("localassemble.run_tool", return_value=valid):
            media = localassemble.probe_video("trusted-ffprobe", Path("assembled.mp4"), assembled=True)
        self.assertEqual(media["codec"], "h264")
        self.assertEqual(media["decodedFrames"], 5400)
        self.assertEqual(media["durationSeconds"], 180.0)

        invalid = json.dumps({
            "streams": [{"codec_type": "video", "codec_name": "hevc", "width": 1440,
                         "height": 1360, "pix_fmt": "yuv420p", "nb_read_frames": "5400"}],
            "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "180.0"},
        })
        with patch("localassemble.run_tool", return_value=invalid):
            with self.assertRaisesRegex(localassemble.ValidationFailure, "video_codec_invalid"):
                localassemble.probe_video("trusted-ffprobe", Path("assembled.mp4"), assembled=True)

    def test_raw_recordings_require_vp8_or_vp9_in_webm(self):
        for codec in ("vp8", "vp9"):
            valid = json.dumps({
                "streams": [{"codec_type": "video", "codec_name": codec, "width": 720,
                             "height": 1280, "pix_fmt": "yuv420p", "nb_read_frames": "900"}],
                "format": {"format_name": "matroska,webm", "duration": "180.0"},
            })
            with patch("localassemble.run_tool", return_value=valid):
                media = localassemble.probe_video("trusted-ffprobe", Path("recording.webm"))
            self.assertEqual(media["codec"], codec)
            self.assertEqual(media["container"], "matroska,webm")

        wrong_container = json.dumps({
            "streams": [{"codec_type": "video", "codec_name": "vp8", "width": 720,
                         "height": 1280, "pix_fmt": "yuv420p", "nb_read_frames": "900"}],
            "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "180.0"},
        })
        with patch("localassemble.run_tool", return_value=wrong_container):
            with self.assertRaisesRegex(localassemble.ValidationFailure, "video_container_invalid"):
                localassemble.probe_video("trusted-ffprobe", Path("recording.webm"))

    def test_full_decode_uses_video_stream_and_null_sink(self):
        with patch("localassemble.run_tool") as run:
            localassemble.fully_decode("trusted-ffmpeg", Path("recording.mp4"))
        self.assertEqual(run.call_args.args[:5],
                         ("trusted-ffmpeg", "-v", "error", "-xerror", "-i"))
        self.assertEqual(run.call_args.args[-5:], ("-map", "0:v:0", "-f", "null", "-"))
        self.assertEqual(run.call_args.kwargs, {"timeout": 240})

    def test_concurrent_sidecar_is_never_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            temporary = root / "temporary.mp4"
            receipt_temporary = root / "temporary.json"
            output = root / "result.mp4"
            sidecar = root / "result.json"
            temporary.write_bytes(b"synthetic encoded bytes")
            receipt_temporary.write_text('{"synthetic":true}\n')
            sidecar.write_text("concurrent owner\n")

            with self.assertRaises(FileExistsError):
                localassemble.publish_output(temporary, output, receipt_temporary, sidecar)
            self.assertFalse(output.exists())
            self.assertEqual(sidecar.read_text(), "concurrent owner\n")


if __name__ == "__main__":
    unittest.main()
