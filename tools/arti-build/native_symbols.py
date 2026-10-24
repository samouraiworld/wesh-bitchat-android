#!/usr/bin/env python3
"""Build and verify deterministic Android native-symbol release artifacts."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import zipfile


ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
LIBRARY = "libarti_android.so"
ARCHIVE = "wesh-android-native-debug-symbols.zip"
BUILDINFO = "NATIVE-SYMBOLS-BUILDINFO.json"
CHECKSUMS = "NATIVE-SYMBOLS-SHA256SUMS"
FORBIDDEN_PATHS = (
    b"/Users/",
    b"/home/",
    b"/workspace/",
    b"/.arti-source/",
    b"/.reproducible-build/",
    b":\\Users\\",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def manifest(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        fields = line.split(None, 1)
        if len(fields) != 2 or not re.fullmatch(r"[0-9a-f]{64}", fields[0]):
            raise ValueError(f"invalid checksum entry in {path.name}")
        if fields[1] in entries:
            raise ValueError(f"duplicate checksum path in {path.name}")
        entries[fields[1]] = fields[0]
    return entries


class Elf:
    """Small, fail-closed ELF section reader used by the release gate."""

    SHT_SYMTAB = 2

    def __init__(self, data: bytes):
        self.data = data
        if len(data) < 64 or data[:4] != b"\x7fELF":
            raise ValueError("not an ELF file")
        elf_class, encoding = data[4], data[5]
        if elf_class not in (1, 2) or encoding not in (1, 2):
            raise ValueError("unsupported ELF class or byte order")
        self.bits = 32 if elf_class == 1 else 64
        self.endian = "<" if encoding == 1 else ">"
        header_format = self.endian + ("16sHHIIIIIHHHHHH" if self.bits == 32 else "16sHHIQQQIHHHHHH")
        header = struct.unpack_from(header_format, data)
        self.shoff = header[6]
        self.shentsize = header[11]
        self.shnum = header[12]
        self.shstrndx = header[13]
        expected_size = 40 if self.bits == 32 else 64
        if self.shentsize < expected_size or self.shnum == 0 or self.shstrndx >= self.shnum:
            raise ValueError("invalid ELF section table")
        if self.shoff + self.shentsize * self.shnum > len(data):
            raise ValueError("truncated ELF section table")
        self.sections = [self._section(index) for index in range(self.shnum)]
        shstr = self.sections[self.shstrndx]
        names = self._slice(shstr[4], shstr[5])
        self.named = {}
        for section in self.sections:
            name = self._cstring(names, section[0])
            if name in self.named:
                raise ValueError(f"duplicate ELF section: {name}")
            self.named[name] = section

    def _section(self, index: int) -> tuple[int, ...]:
        offset = self.shoff + index * self.shentsize
        fmt = self.endian + ("IIIIIIIIII" if self.bits == 32 else "IIQQQQIIQQ")
        return struct.unpack_from(fmt, self.data, offset)

    def _slice(self, offset: int, size: int) -> bytes:
        if offset < 0 or size < 0 or offset + size > len(self.data):
            raise ValueError("ELF section exceeds file")
        return self.data[offset:offset + size]

    @staticmethod
    def _cstring(table: bytes, offset: int) -> str:
        if offset < 0 or offset >= len(table):
            raise ValueError("invalid ELF string offset")
        end = table.find(b"\0", offset)
        if end < 0:
            raise ValueError("unterminated ELF string")
        return table[offset:end].decode("utf-8", "strict")

    def build_id(self) -> str:
        section = self.named.get(".note.gnu.build-id")
        if section is None:
            raise ValueError("missing GNU build-id")
        data = self._slice(section[4], section[5])
        cursor = 0
        while cursor + 12 <= len(data):
            namesz, descsz, note_type = struct.unpack_from(self.endian + "III", data, cursor)
            cursor += 12
            name = data[cursor:cursor + namesz].rstrip(b"\0")
            cursor += (namesz + 3) & ~3
            desc = data[cursor:cursor + descsz]
            cursor += (descsz + 3) & ~3
            if name == b"GNU" and note_type == 3 and desc:
                return desc.hex()
        raise ValueError("invalid GNU build-id note")

    def symbols(self) -> list[str]:
        table = next((section for section in self.sections if section[1] == self.SHT_SYMTAB), None)
        if table is None or table[9] == 0 or table[6] >= len(self.sections):
            return []
        strings_section = self.sections[table[6]]
        strings = self._slice(strings_section[4], strings_section[5])
        raw = self._slice(table[4], table[5])
        minimum = 16 if self.bits == 32 else 24
        if table[9] < minimum or len(raw) % table[9] != 0:
            raise ValueError("invalid ELF symbol table")
        result = []
        for offset in range(0, len(raw), table[9]):
            name_offset = struct.unpack_from(self.endian + "I", raw, offset)[0]
            if name_offset:
                result.append(self._cstring(strings, name_offset))
        return result


def reject_private_paths(path: Path) -> None:
    data = path.read_bytes()
    if any(marker in data for marker in FORBIDDEN_PATHS):
        raise ValueError(f"host-specific build path in {path.name}")


def inspect_pair(symbol: Path, packaged: Path) -> dict[str, object]:
    reject_private_paths(symbol)
    reject_private_paths(packaged)
    symbol_elf = Elf(symbol.read_bytes())
    packaged_elf = Elf(packaged.read_bytes())
    required_debug = {".debug_info", ".debug_line", ".debug_abbrev"}
    missing = required_debug.difference(symbol_elf.named)
    if missing or ".symtab" not in symbol_elf.named:
        raise ValueError(f"unstripped library lacks debug data: {sorted(missing)}")
    rust_symbols = [name for name in symbol_elf.symbols() if "arti" in name or "tor_" in name]
    if not rust_symbols:
        raise ValueError("unstripped library lacks identifiable Arti/Rust symbols")
    if ".symtab" in packaged_elf.named or any(name.startswith(".debug") for name in packaged_elf.named):
        raise ValueError("packaged library still contains non-release symbols")
    symbol_id = symbol_elf.build_id()
    packaged_id = packaged_elf.build_id()
    if symbol_id != packaged_id:
        raise ValueError("stripped and unstripped GNU build-ids differ")
    return {
        "buildId": symbol_id,
        "packagedSha256": sha256(packaged),
        "symbolSha256": sha256(symbol),
        "symbolSize": symbol.stat().st_size,
    }


def zip_datetime(epoch: int) -> tuple[int, int, int, int, int, int]:
    value = dt.datetime.fromtimestamp(max(epoch, 315532800), tz=dt.timezone.utc)
    return value.year, value.month, value.day, value.hour, value.minute, value.second // 2 * 2


def write_archive(symbols_root: Path, destination: Path, epoch: int) -> None:
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        archive.comment = b""
        for abi in ABIS:
            relative = f"lib/{abi}/{LIBRARY}"
            info = zipfile.ZipInfo(relative, date_time=zip_datetime(epoch))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.extra = b""
            archive.writestr(info, (symbols_root / abi / LIBRARY).read_bytes(), compresslevel=9)


def expected_archive_names() -> list[str]:
    return [f"lib/{abi}/{LIBRARY}" for abi in ABIS]


def require_exact_archive_names(names: list[str]) -> None:
    if names != expected_archive_names():
        raise ValueError("native-symbol archive inventory is incomplete, unordered, or unexpected")


def verify_archive(archive_path: Path, expected: dict[str, dict[str, object]]) -> None:
    with zipfile.ZipFile(archive_path) as archive:
        require_exact_archive_names(archive.namelist())
        for abi, name in zip(ABIS, expected_archive_names()):
            data = archive.read(name)
            if hashlib.sha256(data).hexdigest() != expected[abi]["symbolSha256"]:
                raise ValueError(f"native-symbol archive digest mismatch for {abi}")
            reject = any(marker in data for marker in FORBIDDEN_PATHS)
            if reject:
                raise ValueError(f"native-symbol archive contains host path for {abi}")


def package(args: argparse.Namespace) -> None:
    symbols_root = args.symbols_root.resolve()
    packaged_root = args.packaged_root.resolve()
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        raise ValueError(f"output directory is not empty: {output}")
    output.mkdir(parents=True, exist_ok=True)
    packaged_checksums = manifest(args.packaged_manifest)
    expected_paths = {f"app/src/main/jniLibs/{abi}/{LIBRARY}" for abi in ABIS}
    if set(packaged_checksums) != expected_paths:
        raise ValueError("packaged native manifest must contain exactly four ABIs")
    libraries: dict[str, dict[str, object]] = {}
    for abi in ABIS:
        symbol = symbols_root / abi / LIBRARY
        packaged = packaged_root / abi / LIBRARY
        if not symbol.is_file() or not packaged.is_file():
            raise ValueError(f"missing native library for {abi}")
        details = inspect_pair(symbol, packaged)
        expected_sha = packaged_checksums[f"app/src/main/jniLibs/{abi}/{LIBRARY}"]
        if details["packagedSha256"] != expected_sha:
            raise ValueError(f"packaged native manifest mismatch for {abi}")
        libraries[abi] = details
    epoch = int(os.environ["SOURCE_DATE_EPOCH"])
    archive_path = output / ARCHIVE
    write_archive(symbols_root, archive_path, epoch)
    verify_archive(archive_path, libraries)
    buildinfo = {
        "schemaVersion": 1,
        "sourceDateEpoch": epoch,
        "sourceManifestSha256": sha256(args.source_manifest),
        "packagedManifestSha256": sha256(args.packaged_manifest),
        "libraries": libraries,
    }
    buildinfo_path = output / BUILDINFO
    buildinfo_path.write_text(json.dumps(buildinfo, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    checksums_path = output / CHECKSUMS
    checksums_path.write_text(
        "".join(f"{sha256(output / name)}  {name}\n" for name in (BUILDINFO, ARCHIVE)),
        encoding="utf-8",
    )


def find_aab_library(names: set[str], abi: str) -> str:
    candidates = [name for name in names if name.endswith(f"/lib/{abi}/{LIBRARY}")]
    if len(candidates) != 1:
        raise ValueError(f"AAB must contain exactly one packaged library for {abi}")
    return candidates[0]


def verify_release(args: argparse.Namespace) -> None:
    buildinfo = json.loads(args.buildinfo.read_text(encoding="utf-8"))
    libraries = buildinfo.get("libraries", {})
    if set(libraries) != set(ABIS):
        raise ValueError("symbol build information does not cover four ABIs")
    with zipfile.ZipFile(args.archive) as symbols, zipfile.ZipFile(args.aab) as aab:
        require_exact_archive_names(symbols.namelist())
        symbol_names = set(symbols.namelist())
        aab_names = set(aab.namelist())
        for abi in ABIS:
            symbol_name = f"lib/{abi}/{LIBRARY}"
            if symbol_name not in symbol_names:
                raise ValueError(f"symbol archive is missing {abi}")
            symbol_data = symbols.read(symbol_name)
            packaged_data = aab.read(find_aab_library(aab_names, abi))
            if hashlib.sha256(symbol_data).hexdigest() != libraries[abi]["symbolSha256"]:
                raise ValueError(f"symbol digest mismatch for {abi}")
            if hashlib.sha256(packaged_data).hexdigest() != libraries[abi]["packagedSha256"]:
                raise ValueError(f"AAB packaged digest mismatch for {abi}")
            symbol_elf, packaged_elf = Elf(symbol_data), Elf(packaged_data)
            if symbol_elf.build_id() != packaged_elf.build_id() or symbol_elf.build_id() != libraries[abi]["buildId"]:
                raise ValueError(f"GNU build-id mismatch for {abi}")
            if ".symtab" not in symbol_elf.named or ".debug_info" not in symbol_elf.named:
                raise ValueError(f"symbol archive is not unstripped for {abi}")
            if ".symtab" in packaged_elf.named or any(name.startswith(".debug") for name in packaged_elf.named):
                raise ValueError(f"AAB contains unstripped symbols for {abi}")
    print("Native symbol archive matches all four stripped AAB libraries by digest and GNU build-id.")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    sub = result.add_subparsers(dest="command", required=True)
    create = sub.add_parser("package")
    create.add_argument("--symbols-root", type=Path, required=True)
    create.add_argument("--packaged-root", type=Path, required=True)
    create.add_argument("--source-manifest", type=Path, required=True)
    create.add_argument("--packaged-manifest", type=Path, required=True)
    create.add_argument("--output", type=Path, required=True)
    create.set_defaults(function=package)
    verify = sub.add_parser("verify-release")
    verify.add_argument("--archive", type=Path, required=True)
    verify.add_argument("--buildinfo", type=Path, required=True)
    verify.add_argument("--aab", type=Path, required=True)
    verify.set_defaults(function=verify_release)
    return result


def main() -> None:
    args = parser().parse_args()
    try:
        args.function(args)
    except (OSError, ValueError, KeyError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        raise SystemExit(f"error: {error}") from error


if __name__ == "__main__":
    main()
