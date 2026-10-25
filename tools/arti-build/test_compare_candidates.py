#!/usr/bin/env python3
"""Synthetic artifact-contract tests; no native compilation or network access."""

import hashlib
import json
from pathlib import Path
import shutil
import stat
import struct
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile

import compare_candidates as gate
import native_symbols


SOURCE_FILES = (
    "Cargo.lock", "Cargo.toml", "Dockerfile", "TOOLCHAIN.env",
    "rust-toolchain.toml", "build-arti.sh", "native_symbols.py",
    "provenance.sh", "rebuild-in-container.sh", "src/lib.rs",
)


def synthetic_elf(build_id: bytes, debug: bool) -> bytes:
    """Minimal synthetic ELF sections for the real existing symbol parser."""
    note = struct.pack("<III", 4, len(build_id), 3) + b"GNU\0" + build_id
    sections = [("", b"", 0), (".shstrtab", b"", 3), (".note.gnu.build-id", note, 7)]
    if debug:
        sections += [(name, b"synthetic", 1) for name in (".debug_info", ".debug_line", ".debug_abbrev")]
        sections += [
            (".strtab", b"\0tor_synthetic_fixture\0", 3),
            (".symtab", struct.pack("<IBBHQQ", 1, 0, 0, 0, 0, 0), 2),
        ]
    names = b"\0" + b"".join(name.encode() + b"\0" for name, _, _ in sections[1:])
    sections[1] = (".shstrtab", names, 3)
    data = bytearray(64)
    table = []
    for name, content, kind in sections:
        offset = len(data)
        data += content
        table.append((names.index(name.encode() + b"\0"), kind, 0, 0, offset, len(content),
                      len(sections) - 2 if kind == 2 else 0, 0, 1, 24 if kind == 2 else 0))
    table_offset = len(data)
    for section in table:
        data += struct.pack("<IIQQQQIIQQ", *section)
    data[:64] = struct.pack(
        "<16sHHIQQQIHHHHHH", b"\x7fELF\x02\x01\x01" + bytes(9),
        3, 62, 1, 0, 0, table_offset, 0, 64, 0, 0, 64, len(sections), 1,
    )
    return bytes(data)


def write_manifest(path: Path, entries: dict[str, str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(f"{digest}  {name}\n" for name, digest in entries.items()), encoding="utf-8")


class CandidateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="native-candidate-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        source = Path(__file__).parent
        for name in (*SOURCE_FILES, "verify-checksums.sh"):
            destination = self.repository / "tools/arti-build" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, destination)
        self.first = self.root / "first"
        self.second = self.root / "second"
        self.make_candidate(self.first)
        shutil.copytree(self.first, self.second)

    def make_candidate(self, destination):
        symbols = self.root / "synthetic-symbols"
        packaged = destination / "jniLibs"
        for abi in gate.ABIS:
            identifier = hashlib.sha1(("synthetic-" + abi).encode()).digest()
            for root, debug in ((symbols, True), (packaged, False)):
                path = root / abi / gate.LIBRARY
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(synthetic_elf(identifier, debug))
        write_manifest(destination / "manifests/SHA256SUMS", {
            f"app/src/main/jniLibs/{abi}/{gate.LIBRARY}": native_symbols.sha256(packaged / abi / gate.LIBRARY)
            for abi in gate.ABIS
        })
        write_manifest(destination / "manifests/SOURCE_SHA256SUMS", {
            f"tools/arti-build/{name}": native_symbols.sha256(self.repository / "tools/arti-build" / name)
            for name in SOURCE_FILES
        })
        with mock.patch.dict("os.environ", {"SOURCE_DATE_EPOCH": "1785193050"}):
            native_symbols.package(SimpleNamespace(
                symbols_root=symbols, packaged_root=packaged,
                source_manifest=destination / "manifests/SOURCE_SHA256SUMS",
                packaged_manifest=destination / "manifests/SHA256SUMS",
                output=destination / "symbols",
            ))

    def refresh_symbol_checksums(self, candidate):
        symbols = candidate / "symbols"
        write_manifest(symbols / native_symbols.CHECKSUMS, {
            name: native_symbols.sha256(symbols / name)
            for name in (native_symbols.BUILDINFO, native_symbols.ARCHIVE)
        })

    def change_buildinfo(self, candidate, change):
        path = candidate / "symbols" / native_symbols.BUILDINFO
        data = json.loads(path.read_text())
        change(data)
        path.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n")
        self.refresh_symbol_checksums(candidate)

    def rewrite_zip(self, candidate, transform):
        path = candidate / "symbols" / native_symbols.ARCHIVE
        with zipfile.ZipFile(path) as archive:
            entries = [(entry, archive.read(entry)) for entry in archive.infolist()]
        with zipfile.ZipFile(path, "w") as archive:
            for entry, data in transform(entries):
                archive.writestr(entry, data)
        self.refresh_symbol_checksums(candidate)

    def test_complete_pair_is_staged_and_passes_real_unchanged_checksum_script(self):
        with mock.patch.object(gate, "verify_checkout") as checkout:
            gate.verify_and_stage(self.first, self.second, self.repository, "a" * 40)
        self.assertEqual(checkout.call_count, 2)
        for abi in gate.ABIS:
            self.assertEqual(
                (self.repository / "app/src/main/jniLibs" / abi / gate.LIBRARY).read_bytes(),
                (self.first / "jniLibs" / abi / gate.LIBRARY).read_bytes(),
            )

    def test_missing_or_extra_abi_is_rejected(self):
        (self.first / "jniLibs/x86" / gate.LIBRARY).unlink()
        with self.assertRaisesRegex(ValueError, "nine expected"):
            gate.validate_candidate(self.first, self.repository)
        shutil.copyfile(self.second / "jniLibs/x86" / gate.LIBRARY, self.first / "jniLibs/x86" / gate.LIBRARY)
        (self.first / "jniLibs/extra").mkdir()
        with self.assertRaisesRegex(ValueError, "unexpected directory"):
            gate.validate_candidate(self.first, self.repository)

    def test_candidate_file_and_directory_symlinks_are_rejected(self):
        for directory in (False, True):
            with self.subTest(directory=directory):
                candidate = self.root / ("symlink-directory" if directory else "symlink-file")
                shutil.copytree(self.first, candidate)
                path = candidate / "jniLibs/x86"
                if not directory:
                    path = path / gate.LIBRARY
                    path.unlink()
                    path.symlink_to(self.first / "jniLibs/x86" / gate.LIBRARY)
                else:
                    shutil.rmtree(path)
                    path.symlink_to(self.first / "jniLibs/x86", target_is_directory=True)
                with self.assertRaisesRegex(ValueError, "symlink"):
                    gate.validate_candidate(candidate, self.repository)

    def test_manifest_traversal_and_duplicate_paths_are_rejected(self):
        manifest = self.first / "manifests/SHA256SUMS"
        for text, message in (("0" * 64 + "  ../escape\n", "unsafe"),
                              (("0" * 64 + "  file\n") * 2, "duplicate")):
            with self.subTest(message=message):
                manifest.write_text(text)
                with self.assertRaisesRegex(ValueError, message):
                    gate.validate_candidate(self.first, self.repository)

    def test_incorrect_library_hash_is_rejected(self):
        (self.first / "jniLibs/x86" / gate.LIBRARY).write_bytes(b"synthetic mismatch")
        with self.assertRaisesRegex(ValueError, "library checksum mismatch"):
            gate.validate_candidate(self.first, self.repository)

    def test_source_manifest_cannot_bind_a_different_checkout(self):
        (self.repository / "tools/arti-build/src/lib.rs").write_text("synthetic source drift\n")
        with self.assertRaisesRegex(ValueError, "source does not match"):
            gate.validate_candidate(self.first, self.repository)

    def test_buildinfo_manifest_binding_cannot_be_replaced(self):
        self.change_buildinfo(self.first, lambda data: data.update(sourceManifestSha256="0" * 64))
        with self.assertRaisesRegex(ValueError, "not bound"):
            gate.validate_candidate(self.first, self.repository)

    def test_extra_or_traversal_zip_entry_is_rejected_before_extraction(self):
        for suffix in ("extra", "../escape"):
            with self.subTest(suffix=suffix):
                candidate = self.root / ("extra-zip" if suffix == "extra" else "traversal-zip")
                shutil.copytree(self.first, candidate)
                self.rewrite_zip(candidate, lambda entries: entries + [(suffix, b"synthetic")])
                with self.assertRaisesRegex(ValueError, "inventory.*unexpected"):
                    gate.validate_candidate(candidate, self.repository)
                self.assertFalse((self.root / "escape").exists())

    def test_zip_symlink_entry_is_rejected(self):
        def make_symlink(entries):
            entries[0][0].external_attr = (stat.S_IFLNK | 0o777) << 16
            return entries
        self.rewrite_zip(self.first, make_symlink)
        with self.assertRaisesRegex(ValueError, "regular file"):
            gate.validate_candidate(self.first, self.repository)

    def test_symbol_metadata_cannot_point_to_an_unrelated_library(self):
        self.change_buildinfo(self.first, lambda data: data["libraries"][gate.ABIS[0]].update(packagedSha256="0" * 64))
        with self.assertRaisesRegex(ValueError, "do not match"):
            gate.validate_candidate(self.first, self.repository)

    def test_different_symbol_and_packaged_build_ids_cannot_be_hidden_by_hashes(self):
        abi = gate.ABIS[0]
        path = self.first / "jniLibs" / abi / gate.LIBRARY
        path.write_bytes(synthetic_elf(bytes(20), False))
        packaged = self.first / "manifests/SHA256SUMS"
        entries = gate.manifest(packaged)
        entries[f"app/src/main/jniLibs/{abi}/{gate.LIBRARY}"] = native_symbols.sha256(path)
        write_manifest(packaged, entries)
        def refresh(data):
            data["packagedManifestSha256"] = native_symbols.sha256(packaged)
            data["libraries"][abi]["packagedSha256"] = native_symbols.sha256(path)
            data["libraries"][abi]["buildId"] = bytes(20).hex()
        self.change_buildinfo(self.first, refresh)
        with self.assertRaisesRegex(ValueError, "build-ids differ"):
            gate.validate_candidate(self.first, self.repository)

    def test_changed_symbol_bytes_fail_even_when_outer_checksums_are_refreshed(self):
        def change_bytes(entries):
            entry, data = entries[0]
            entries[0] = entry, data[:-1] + bytes([data[-1] ^ 1])
            return entries
        self.rewrite_zip(self.first, change_bytes)
        with self.assertRaises((ValueError, struct.error)):
            gate.validate_candidate(self.first, self.repository)

    def test_equal_valid_metadata_with_different_bytes_is_not_reproducible(self):
        self.change_buildinfo(self.second, lambda data: None)
        # JSON whitespace changes bytes without changing the valid object.
        path = self.second / "symbols" / native_symbols.BUILDINFO
        path.write_text(path.read_text() + "\n")
        self.refresh_symbol_checksums(self.second)
        with mock.patch.object(gate, "verify_checkout"):
            with self.assertRaisesRegex(ValueError, "bytes differ"):
                gate.verify_and_stage(self.first, self.second, self.repository, "a" * 40)
        self.assertFalse((self.repository / "app/src/main/jniLibs").exists())

    def test_staging_through_a_symlink_is_rejected(self):
        external = self.root / "external"
        external.mkdir()
        (self.repository / "app").symlink_to(external, target_is_directory=True)
        with mock.patch.object(gate, "verify_checkout"):
            with self.assertRaisesRegex(ValueError, "symlink"):
                gate.verify_and_stage(self.first, self.second, self.repository, "a" * 40)
        self.assertEqual(list(external.iterdir()), [])

    def test_exact_source_inventory_is_still_enforced_by_unchanged_gate(self):
        for candidate in (self.first, self.second):
            path = candidate / "manifests/SOURCE_SHA256SUMS"
            lines = path.read_text().splitlines(keepends=True)
            path.write_text("".join(lines[1:]))
            self.change_buildinfo(candidate, lambda data: data.update(sourceManifestSha256=native_symbols.sha256(path)))
        with mock.patch.object(gate, "verify_checkout"):
            with self.assertRaises(subprocess.CalledProcessError):
                gate.verify_and_stage(self.first, self.second, self.repository, "a" * 40)

    def test_checkout_and_dirty_source_must_match_workflow_sha(self):
        with mock.patch.object(gate.subprocess, "check_output", return_value="b" * 40):
            with self.assertRaisesRegex(ValueError, "GITHUB_SHA"):
                gate.verify_checkout(self.repository, "a" * 40)
        with mock.patch.object(gate.subprocess, "check_output", return_value="a" * 40):
            with mock.patch.object(gate.subprocess, "run", return_value=SimpleNamespace(returncode=1)):
                with self.assertRaisesRegex(ValueError, "tracked source differs"):
                    gate.verify_checkout(self.repository, "a" * 40)


if __name__ == "__main__":
    unittest.main()
