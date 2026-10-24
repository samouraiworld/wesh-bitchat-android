#!/usr/bin/env python3
"""Static regression tests for the native-symbol release gates."""

import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import zipfile


MODULE_PATH = Path(__file__).with_name("native_symbols.py")
SPEC = importlib.util.spec_from_file_location("native_symbols", MODULE_PATH)
native_symbols = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(native_symbols)


class NativeSymbolsTests(unittest.TestCase):
    def test_committed_packaged_libraries_are_stripped_elf_with_build_ids(self):
        root = Path(__file__).resolve().parents[2]
        for abi in native_symbols.ABIS:
            with self.subTest(abi=abi):
                path = root / "app/src/main/jniLibs" / abi / native_symbols.LIBRARY
                elf = native_symbols.Elf(path.read_bytes())
                self.assertNotIn(".symtab", elf.named)
                self.assertFalse(any(name.startswith(".debug") for name in elf.named))
                self.assertRegex(elf.build_id(), r"^[0-9a-f]+$")

    def test_missing_build_id_is_rejected(self):
        elf = object.__new__(native_symbols.Elf)
        elf.named = {}
        with self.assertRaisesRegex(ValueError, "missing GNU build-id"):
            elf.build_id()

    def test_archive_is_deterministic_and_ordered(self):
        with tempfile.TemporaryDirectory(prefix="native-symbol-test-") as directory:
            root = Path(directory)
            symbols = root / "symbols"
            for abi in native_symbols.ABIS:
                path = symbols / abi / native_symbols.LIBRARY
                path.parent.mkdir(parents=True)
                path.write_bytes((abi + "\n").encode())
            first, second = root / "first.zip", root / "second.zip"
            native_symbols.write_archive(symbols, first, 1785193050)
            native_symbols.write_archive(symbols, second, 1785193050)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(
                    archive.namelist(),
                    [f"lib/{abi}/{native_symbols.LIBRARY}" for abi in native_symbols.ABIS],
                )

    def test_private_paths_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="native-symbol-test-") as directory:
            path = Path(directory) / "library.so"
            for marker in native_symbols.FORBIDDEN_PATHS:
                with self.subTest(marker=marker):
                    path.write_bytes(b"prefix" + marker + b"suffix")
                    with self.assertRaisesRegex(ValueError, "host-specific"):
                        native_symbols.reject_private_paths(path)

    def test_release_verifier_rejects_extra_archive_entry(self):
        with tempfile.TemporaryDirectory(prefix="native-symbol-test-") as directory:
            root = Path(directory)
            archive_path = root / "symbols.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                for name in native_symbols.expected_archive_names():
                    archive.writestr(name, b"not-reached")
                archive.writestr("unexpected.txt", b"reject me")
            aab_path = root / "app.aab"
            with zipfile.ZipFile(aab_path, "w"):
                pass
            buildinfo_path = root / "buildinfo.json"
            buildinfo_path.write_text(
                json.dumps({"libraries": {abi: {} for abi in native_symbols.ABIS}}),
                encoding="utf-8",
            )
            args = SimpleNamespace(
                archive=archive_path,
                aab=aab_path,
                buildinfo=buildinfo_path,
            )
            with self.assertRaisesRegex(ValueError, "inventory.*unexpected"):
                native_symbols.verify_release(args)

    def test_invalid_or_duplicate_manifest_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="native-symbol-test-") as directory:
            path = Path(directory) / "manifest"
            path.write_text("not-a-digest  file\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "invalid checksum"):
                native_symbols.manifest(path)
            digest = "0" * 64
            path.write_text(f"{digest}  file\n{digest}  file\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                native_symbols.manifest(path)

    def test_publication_gate_binds_symbol_archive_to_unsigned_aab(self):
        root = Path(__file__).resolve().parents[2]
        script = (
            root / "tools" / "reproducible-builds" / "prepare-github-release.sh"
        ).read_text(encoding="utf-8")
        self.assertIn('native_symbols.py" verify-release', script)
        self.assertIn('--archive "$RELEASE_DIR/wesh-android-native-debug-symbols.zip"', script)
        self.assertIn('--buildinfo "$RELEASE_DIR/NATIVE-SYMBOLS-BUILDINFO.json"', script)
        self.assertIn('--aab "$RELEASE_DIR/bitchat-android-release-unsigned.aab"', script)

    def test_native_dependency_dwarf_paths_are_remapped(self):
        script = Path(__file__).with_name("build-arti.sh").read_text(encoding="utf-8")
        native_block = script.split("REPRO_NATIVE_PREFIX_MAPS=(", 1)[1].split("\n)", 1)[0]
        project = native_block.index('"$PROJECT_ROOT=/usr/src/bitchat-android"')
        arti = native_block.index('"$ARTI_SOURCE_DIR=/usr/src/arti"')
        cargo = native_block.index('"$CARGO_HOME_PATH=/usr/local/cargo"')
        self.assertLess(project, arti)
        self.assertLess(arti, cargo)
        for option in ("fdebug-prefix-map", "ffile-prefix-map", "fmacro-prefix-map"):
            self.assertIn(option, script)
        self.assertIn("export CFLAGS", script)
        self.assertIn("export CXXFLAGS", script)


if __name__ == "__main__":
    unittest.main()
