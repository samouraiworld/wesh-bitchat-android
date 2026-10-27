import importlib.util
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from PIL import Image, PngImagePlugin

HERE = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


capture = load("store_capture_manifest")
boundary = load("verify_capture_release_boundary")
stability = load("wait_store_capture_stability")


class CaptureEvidenceTests(unittest.TestCase):
    def test_candidate_binding_and_capture_require_explicit_dispatch(self):
        self.assertEqual(
            capture.PRODUCTION_COMMIT,
            "753769a07fa9e62c87afead88ea10ef42b823788",
        )
        workflow = (HERE.parents[1] / ".github/workflows/store-capture.yml").read_text()
        for step in (
            "Prepare isolated CI emulator",
            "Capture fourteen synthetic product-view scenes",
            "Retain only sanitized synthetic images and source manifest",
        ):
            self.assertRegex(
                workflow,
                rf"- name: {re.escape(step)}\n\s+if: \$\{{\{{ github\.event_name == 'workflow_dispatch' \}}\}}",
            )
        self.assertRegex(
            workflow,
            r"- name: Stop isolated emulator\n\s+if: \$\{\{ always\(\) && "
            r"github\.event_name == 'workflow_dispatch' \}\}",
        )
        self.assertIn("name: android-store-candidate108-unbound", workflow)
        self.assertIn("path: ${{ runner.temp }}/android-store-candidate108-unbound", workflow)
        self.assertNotIn("android-store-candidate107-unbound", workflow)

    def test_sanitizer_removes_metadata_before_evidence_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            metadata = PngImagePlugin.PngInfo()
            metadata.add_text("Comment", "synthetic metadata, never retained")
            Image.new("RGBA", (1080, 1920), (0, 0, 0, 255)).save(folder / "raw.png", pnginfo=metadata)
            subprocess.run([sys.executable, str(HERE / "sanitize_store_screenshot.py"), str(folder / "raw.png"), str(folder / "clean.png")], check=True)
            capture.image_record(folder, "clean.png")

    def test_valid_opaque_png_has_a_content_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            Image.new("RGBA", (1080, 1920), (12, 24, 36, 255)).save(folder / "test.png")
            record = capture.image_record(folder, "test.png")
            self.assertEqual(record["width"], 1080)
            self.assertEqual(record["height"], 1920)
            self.assertEqual(record["sha256"], capture.digest(folder / "test.png"))

    def test_metadata_and_transparency_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            image = Image.new("RGBA", (1080, 1920), (0, 0, 0, 255))
            metadata = PngImagePlugin.PngInfo()
            metadata.add_text("Comment", "synthetic metadata must be removed")
            image.save(folder / "test.png", pnginfo=metadata)
            with self.assertRaisesRegex(ValueError, "metadata"):
                capture.image_record(folder, "test.png")
            Image.new("RGBA", (1080, 1920), (0, 0, 0, 0)).save(folder / "test.png")
            with self.assertRaisesRegex(ValueError, "transparency"):
                capture.image_record(folder, "test.png")

    def test_missing_and_extra_scenes_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "fourteen"):
                capture.image_records(Path(directory))

    def test_unrelated_artifact_files_cannot_be_uploaded_with_screenshots(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            for locale in ("fr-FR", "en-US"):
                for index, scene in enumerate(capture.SCENES, 1):
                    file = folder / f"{locale}/phoneScreenshots/{index:02}-{scene}.png"
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_bytes(b"synthetic placeholder; must be rejected before image decoding")
            (folder / "unrelated.txt").write_text("synthetic unrelated data")
            with self.assertRaisesRegex(ValueError, "unexpected artifact"):
                capture.image_records(folder)

    def test_unreviewed_images_never_pass_reviewed_gate(self):
        proof = {"capturedFromCommit": "a" * 40}
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            manifest = {**proof, "synthetic": True, "environment": capture.EXPECTED_ENVIRONMENT, "screenshots": [], "bindingStatus": "UNBOUND_REVIEW_REQUIRED", "captureCommit": None}
            (folder / "manifest.json").write_text(json.dumps(manifest))
            with patch.object(capture, "source_proof", return_value=proof), patch.object(capture, "image_records", return_value=[]):
                capture.verify(folder, folder)
                with self.assertRaisesRegex(ValueError, "review"):
                    capture.verify(folder, folder, require_reviewed=True)
                manifest["capturedFromCommit"] = "b" * 40
                (folder / "manifest.json").write_text(json.dumps(manifest))
                with self.assertRaisesRegex(ValueError, "binding"):
                    capture.verify(folder, folder)

    def test_production_drift_fails_before_capture_binding(self):
        with patch.object(capture.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "synthetic diff")):
            with self.assertRaises(subprocess.CalledProcessError):
                capture.source_proof(Path("."))

    def test_release_fixture_and_class_leaks_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "synthetic.apk"
            for name, content in [("assets/store_scenes.json", b"{}"), ("classes.dex", b"StoreScreenshotActivity")]:
                with zipfile.ZipFile(apk, "w") as output:
                    output.writestr(name, content)
                with self.assertRaisesRegex(ValueError, "leaked"):
                    boundary.verify(apk)
            with zipfile.ZipFile(apk, "w") as output:
                output.writestr("classes.dex", b"synthetic ordinary release")
            boundary.verify(apk)

    def test_context_rejects_wrong_density_or_identifying_extra_fields(self):
        capture.validate_environment(dict(capture.EXPECTED_ENVIRONMENT))
        for extra in ({"densityDpi": 320}, {"hostname": "synthetic-invalid-extra"}):
            with self.assertRaisesRegex(ValueError, "context"):
                capture.validate_environment({**capture.EXPECTED_ENVIRONMENT, **extra})

    def test_stability_waits_for_three_consecutive_equal_pixels(self):
        def frame(color, comment):
            output = io.BytesIO()
            metadata = PngImagePlugin.PngInfo()
            metadata.add_text("Comment", comment)
            Image.new("RGBA", (1080, 1920), color).save(output, format="PNG", pnginfo=metadata)
            return output.getvalue()
        first = frame((0, 0, 0, 255), "synthetic-first")
        last = [frame((1, 2, 3, 255), f"synthetic-{i}") for i in range(3)]
        samples = iter([first, *last])
        elapsed = [0.0]
        def sleep(seconds):
            elapsed[0] += seconds
        result = stability.stable_frame(lambda _: next(samples), clock=lambda: elapsed[0], sleep=sleep)
        self.assertEqual(result, last[-1])
        self.assertGreaterEqual(elapsed[0], 0.6)

    def test_unstable_pixels_fail_at_the_deadline_without_a_frame(self):
        elapsed = [0.0]
        calls = [0]
        def sample(_):
            calls[0] += 1
            return b"synthetic-sample"
        def sleep(seconds):
            elapsed[0] += seconds
        with patch.object(stability, "pixels", side_effect=lambda _: str(calls[0])):
            with self.assertRaises(TimeoutError):
                stability.stable_frame(sample, timeout=1, interval=0.2, clock=lambda: elapsed[0], sleep=sleep)
        self.assertLessEqual(calls[0], 5)


if __name__ == "__main__":
    unittest.main()
