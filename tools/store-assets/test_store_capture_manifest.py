import importlib.util
import io
import json
import os
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
        self.assertIn("WESH_STORE_PRODUCTION_COMMIT", (HERE / "store_capture_manifest.py").read_text())
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
        self.assertIn("production_commit:", workflow)
        self.assertIn("WESH_STORE_PRODUCTION_COMMIT:", workflow)
        self.assertIn("store_capture_manifest.py verify-source", workflow)
        self.assertIn("name: android-store-${{ env.WESH_STORE_PRODUCTION_COMMIT }}-${{ github.sha }}-unbound", workflow)
        self.assertIn("path: ${{ runner.temp }}/android-store-captures", workflow)
        self.assertNotIn("android-store-candidate108-unbound", workflow)

    def test_about_uses_the_actual_variant_version_with_the_capture_suffix_removed(self):
        fixture = (HERE.parents[1] / "app/src/storeScreenshot/java/com/bitchat/android/store/StoreScreenshotActivity.kt").read_text()
        gradle = (HERE.parents[1] / "app/build.gradle.kts").read_text()
        self.assertIn('versionNameSuffix = "-storecapture"', gradle)
        self.assertIn('check(BuildConfig.VERSION_NAME.endsWith("-storecapture"))', fixture)
        self.assertIn('AboutScreen(versionName = BuildConfig.VERSION_NAME.removeSuffix("-storecapture")', fixture)
        self.assertNotRegex(fixture, r'AboutScreen\(versionName = "[0-9]')

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


class ProductionCandidateBindingTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "-q")
        # Synthetic identity belongs only to this disposable test repository.
        self.git("config", "user.name", "Synthetic Test")
        self.git("config", "user.email", "synthetic@example.invalid")
        paths = set(capture.ROOT_PATHS) | set(capture.CAPTURE_PATHS) | {
            "app/gradle.lockfile", "settings-gradle.lockfile", "gradle/libs.versions.toml",
            "gradle/verification-metadata.xml", "settings.gradle.kts", "build.gradle.kts",
            "gradle.properties", "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar",
            "gradlew", "gradlew.bat", "app/proguard-rules.pro",
            "app/src/main/jniLibs/arm64-v8a/libarti_android.so", "tools/arti-build/Cargo.lock",
            "tools/arti-build/provenance.sha256",
        }
        for name in paths:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("synthetic committed input\n")
        self.git("add", ".")
        self.git("commit", "-qm", "Pin synthetic production inputs")
        self.candidate = self.git("rev-parse", "HEAD")
        # The capture commit can change its own fixture without changing product inputs.
        fixture = self.root / "app/src/storeScreenshot/java/com/bitchat/android/store/StoreScreenshotActivity.kt"
        fixture.write_text("synthetic capture-only change\n")
        self.git("add", ".")
        self.git("commit", "-qm", "Prepare synthetic captures")
        self.capture_head = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True, stderr=subprocess.DEVNULL).strip()

    def test_capture_only_commit_binds_to_an_explicit_unchanged_product(self):
        proof = capture.source_proof(self.root, self.candidate)
        self.assertEqual(proof["productionCandidateCommit"], self.candidate)
        self.assertEqual(proof["capturedFromCommit"], self.capture_head)
        self.assertEqual(proof["productionCandidateTree"], self.git("rev-parse", f"{self.candidate}^{{tree}}"))
        self.assertIn("app/gradle.lockfile", proof["productionInputPaths"])
        self.assertIn("tools/arti-build", proof["productionInputPaths"])

    def test_the_environment_pin_is_explicit_and_required(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "production commit"):
                capture.source_proof(self.root)
        with patch.dict(os.environ, {"WESH_STORE_PRODUCTION_COMMIT": self.candidate}):
            self.assertEqual(capture.source_proof(self.root)["productionCandidateCommit"], self.candidate)

    def test_short_moving_and_invalid_pins_are_rejected(self):
        for pin in ["HEAD", "main", "v1.0.1-android.110", self.candidate[:8], "-" * 40, "a" * 39 + "\n"]:
            with self.subTest(pin=pin), self.assertRaisesRegex(ValueError, "full lowercase"):
                capture.source_proof(self.root, pin)
        with self.assertRaises((ValueError, subprocess.CalledProcessError)):
            capture.source_proof(self.root, "f" * 40)

    def test_a_wrong_existing_pin_cannot_bind_different_product_bytes(self):
        target = self.root / "app/src/main/java/com/bitchat/android/ui/pave/WeshPaveApp.kt"
        target.write_text("synthetic changed production input\n")
        self.git("add", ".")
        self.git("commit", "-qm", "Change synthetic product")
        with self.assertRaises(subprocess.CalledProcessError):
            capture.source_proof(self.root, self.candidate)

    def test_native_locks_and_gradle_changes_are_not_capture_only(self):
        for name in ["app/gradle.lockfile", "settings-gradle.lockfile", "gradle/libs.versions.toml",
                     "gradle/verification-metadata.xml", "tools/arti-build/Cargo.lock",
                     "app/src/main/jniLibs/arm64-v8a/libarti_android.so", "app/proguard-rules.pro"]:
            with self.subTest(path=name):
                target = self.root / name
                original = target.read_bytes()
                target.write_bytes(b"synthetic modified dependency or native input\n")
                with self.assertRaisesRegex(ValueError, "uncommitted"):
                    capture.source_proof(self.root, self.candidate)
                target.write_bytes(original)

    def test_committed_native_and_lock_changes_cannot_be_called_capture_only(self):
        for name in ["app/gradle.lockfile", "gradle/verification-metadata.xml",
                     "tools/arti-build/Cargo.lock", "app/src/main/jniLibs/arm64-v8a/libarti_android.so"]:
            with self.subTest(path=name):
                target = self.root / name
                original = target.read_bytes()
                target.write_bytes(b"synthetic committed dependency change\n")
                self.git("add", name)
                self.git("commit", "-qm", "Change synthetic dependency")
                with self.assertRaises(subprocess.CalledProcessError):
                    capture.source_proof(self.root, self.candidate)
                target.write_bytes(original)
                self.git("add", name)
                self.git("commit", "-qm", "Restore synthetic dependency")

    def test_staged_and_unstaged_product_or_fixture_changes_fail(self):
        for name in ["app/build.gradle.kts", "app/src/main/java/com/bitchat/android/MainActivity.kt",
                     "app/src/storeScreenshot/java/com/bitchat/android/store/StoreScreenshotActivity.kt"]:
            with self.subTest(path=name):
                target = self.root / name
                original = target.read_bytes()
                target.write_bytes(b"synthetic dirty input\n")
                with self.assertRaisesRegex(ValueError, "uncommitted"):
                    capture.source_proof(self.root, self.candidate)
                self.git("add", name)
                with self.assertRaisesRegex(ValueError, "uncommitted"):
                    capture.source_proof(self.root, self.candidate)
                target.write_bytes(original)
                self.git("add", name)

    def test_untracked_and_ignored_product_sources_fail(self):
        path = self.root / "app/src/main/java/synthetic/Extra.kt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("synthetic untracked input\n")
        with self.assertRaisesRegex(ValueError, "uncommitted|untracked"):
            capture.source_proof(self.root, self.candidate)
        (self.root / ".git/info/exclude").write_text("Extra.kt\n")
        with self.assertRaisesRegex(ValueError, "untracked"):
            capture.source_proof(self.root, self.candidate)

    def test_a_manifest_cannot_swap_the_candidate_during_verification(self):
        folder = self.root / "synthetic-output"
        folder.mkdir()
        proof = capture.source_proof(self.root, self.candidate)
        manifest = {**proof, "synthetic": True, "environment": capture.EXPECTED_ENVIRONMENT,
                    "screenshots": [], "bindingStatus": "UNBOUND_REVIEW_REQUIRED", "captureCommit": None}
        (folder / "manifest.json").write_text(json.dumps(manifest))
        with patch.object(capture, "image_records", return_value=[]):
            capture.verify(self.root, folder, production_candidate=self.candidate)
            with self.assertRaisesRegex(ValueError, "binding differs: productionCandidateCommit"):
                capture.verify(self.root, folder, production_candidate=self.capture_head)
            manifest["productionInputTreeSHA256"] = "0" * 64
            (folder / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "binding differs: productionInputTreeSHA256"):
                capture.verify(self.root, folder, production_candidate=self.candidate)

    def test_verify_source_cli_does_not_require_images_or_start_an_emulator(self):
        result = subprocess.run([sys.executable, str(HERE / "store_capture_manifest.py"), "verify-source",
                                 "--root", str(self.root), "--production-commit", self.candidate],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Production candidate sources verified", result.stdout)


if __name__ == "__main__":
    unittest.main()
