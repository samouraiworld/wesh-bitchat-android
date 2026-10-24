#!/usr/bin/env python3
"""Exercise native provenance failure modes with synthetic temporary inputs."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class NativeProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="native-provenance-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tool = self.root / "tools/arti-build"
        self.tool.mkdir(parents=True)
        shutil.copyfile(Path(__file__).with_name("verify-checksums.sh"),
                        self.tool / "verify-checksums.sh")
        self.sources = [self.tool / name for name in (
            "Cargo.lock", "Cargo.toml", "Dockerfile", "TOOLCHAIN.env",
            "rust-toolchain.toml", "build-arti.sh", "native_symbols.py", "provenance.sh",
            "rebuild-in-container.sh", "src/lib.rs")]
        self.libraries = [self.root / "app/src/main/jniLibs" / abi / "libarti_android.so"
                          for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")]
        for entry in self.sources + self.libraries:
            entry.parent.mkdir(parents=True, exist_ok=True)
            entry.write_bytes(b"synthetic native provenance fixture\n")
        shutil.copyfile(Path(__file__).with_name("provenance.sh"), self.tool / "provenance.sh")
        for name, entries in (("SOURCE_SHA256SUMS", self.sources),
                              ("SHA256SUMS", self.libraries)):
            (self.tool / name).write_text("".join(
                f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(self.root)}\n"
                for p in entries))

    def verify(self, env=None):
        return subprocess.run(["bash", str(self.tool / "verify-checksums.sh")],
                              cwd=self.root, capture_output=True, text=True, env=env)

    def test_matching_inputs_pass(self):
        self.assertEqual(self.verify().returncode, 0)

    def test_changed_lock_wrapper_or_container_entrypoint_is_rejected(self):
        for entry in (self.sources[0], self.sources[-2], self.sources[-1]):
            original = entry.read_bytes()
            with self.subTest(input=entry.name):
                entry.write_bytes(original + b"synthetic modification\n")
                self.assertNotEqual(self.verify().returncode, 0)
            entry.write_bytes(original)

    def test_changed_library_is_rejected(self):
        self.libraries[0].write_bytes(b"synthetic changed native library\n")
        self.assertNotEqual(self.verify().returncode, 0)

    def test_missing_source_manifest_is_rejected(self):
        (self.tool / "SOURCE_SHA256SUMS").unlink()
        self.assertNotEqual(self.verify().returncode, 0)

    def test_incomplete_or_duplicate_manifest_is_rejected(self):
        manifest = self.tool / "SHA256SUMS"
        lines = manifest.read_text().splitlines(keepends=True)
        for invalid in (lines[:-1], [lines[0]] + lines[:-1]):
            with self.subTest(entry_count=len(invalid)):
                manifest.write_text("".join(invalid))
                self.assertNotEqual(self.verify().returncode, 0)

    def update_library_manifest(self):
        (self.tool / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(self.root)}\n"
            for p in self.libraries))

    def test_windows_host_path_is_rejected(self):
        self.libraries[0].write_bytes(b"synthetic C:\\Users\\placeholder\\native.rs\n")
        self.update_library_manifest()
        self.assertNotEqual(self.verify().returncode, 0)

    def test_large_early_host_path_is_rejected(self):
        self.libraries[0].write_bytes(b"/home/placeholder/native.rs\n" + b"synthetic safe line\n" * 250000)
        self.update_library_manifest()
        self.assertNotEqual(self.verify().returncode, 0)

    def test_strings_failure_is_rejected(self):
        executable = self.root / "bin/strings"
        executable.parent.mkdir()
        executable.write_text("#!/bin/sh\nexit 1\n")
        executable.chmod(0o755)
        env = dict(os.environ, PATH=str(executable.parent) + os.pathsep + os.environ["PATH"])
        self.assertNotEqual(self.verify(env=env).returncode, 0)

    def test_unsupported_patch_is_rejected(self):
        (self.tool / "patches").mkdir()
        (self.tool / "patches/example.patch").write_text("synthetic unsupported input\n")
        self.assertNotEqual(self.verify().returncode, 0)

    def run_helper(self, script):
        return subprocess.run(["bash", "-euo", "pipefail", "-c",
                               'source "$1/provenance.sh"; ' + script,
                               "fixture", str(self.tool), str(self.root / "snapshot")],
                              cwd=self.root, capture_output=True, text=True)

    def test_changed_active_inputs_refuse_publication(self):
        result = self.run_helper('capture_source_inputs "$1" "$2"; '
                                 'printf "changed\\n" >> "$1/Cargo.lock"; '
                                 'assert_source_inputs_unchanged "$1" "$2"')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("sources changed", result.stderr)

    def test_snapshot_stays_fixed_during_active_edit_and_restore(self):
        result = self.run_helper('capture_source_inputs "$1" "$2"; '
                                 'cp "$1/Cargo.lock" original-lock; '
                                 'printf "changed\\n" > "$1/Cargo.lock"; '
                                 '! cmp -s "$1/Cargo.lock" "$2/Cargo.lock"; '
                                 'cmp -s original-lock "$2/Cargo.lock"; '
                                 'cp original-lock "$1/Cargo.lock"; '
                                 'assert_source_inputs_unchanged "$1" "$2"')
        self.assertEqual(result.returncode, 0)

    def test_readelf_failure_is_rejected_even_after_valid_output(self):
        tool = self.root / "fake-readelf"
        tool.write_text("#!/bin/sh\nprintf 'LOAD 0x0 0x0 0x0 0x100 0x100 R E 0x4000\\n'\nexit 1\n")
        tool.chmod(0o755)
        result = self.run_helper('assert_64bit_load_alignment ignored "$(dirname "$1")/../fake-readelf"')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("readelf failed", result.stderr)

    def test_all_load_alignments_are_checked(self):
        tool = self.root / "fake-readelf"
        for alignment, expected in (("0x1000", False), ("0x5000", False), ("0x4000", True)):
            tool.write_text("#!/bin/sh\nprintf 'LOAD 0x0 0x0 0x0 0x100 0x100 R E 0x4000\\n"
                            f"LOAD 0x0 0x0 0x0 0x100 0x100 RW {alignment}\\n'\n")
            tool.chmod(0o755)
            with self.subTest(alignment=alignment):
                result = self.run_helper('assert_64bit_load_alignment ignored "$(dirname "$1")/../fake-readelf"')
                self.assertEqual(result.returncode == 0, expected)

    def test_host_path_is_rejected_even_with_matching_library_hash(self):
        self.libraries[0].write_bytes(b"synthetic /home/placeholder/native.rs\n")
        (self.tool / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(self.root)}\n"
            for p in self.libraries))
        self.assertNotEqual(self.verify().returncode, 0)


if __name__ == "__main__":
    unittest.main()
