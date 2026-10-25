#!/usr/bin/env python3
"""Verify two fresh native candidates before staging a complete candidate set.

This gate deliberately compares new outputs with each other, not with the old
committed native manifests. The unchanged checksum gate runs after staging.
"""

import argparse
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tempfile
import zipfile

import native_symbols


LIBRARY = native_symbols.LIBRARY
ABIS = native_symbols.ABIS
MANIFESTS = ("SHA256SUMS", "SOURCE_SHA256SUMS")
SYMBOL_FILES = (native_symbols.BUILDINFO, native_symbols.CHECKSUMS, native_symbols.ARCHIVE)
LIBRARY_PATHS = {f"app/src/main/jniLibs/{abi}/{LIBRARY}" for abi in ABIS}
FILES = {f"jniLibs/{abi}/{LIBRARY}" for abi in ABIS} | {
    f"manifests/{name}" for name in MANIFESTS
} | {f"symbols/{name}" for name in SYMBOL_FILES}
DIRECTORIES = {
    str(parent)
    for name in FILES
    for parent in PurePosixPath(name).parents
    if str(parent) != "."
}


def inventory(root: Path) -> None:
    if root.is_symlink() or not root.is_dir():
        raise ValueError("candidate root must be a directory, not a symlink")
    files = set()
    for path in root.rglob("*"):
        name = path.relative_to(root).as_posix()
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode):
            raise ValueError("candidate contains a symlink")
        if stat.S_ISREG(mode):
            files.add(name)
        elif not stat.S_ISDIR(mode) or name not in DIRECTORIES:
            raise ValueError("candidate contains an unexpected directory or special file")
    if files != FILES:
        raise ValueError("candidate inventory must contain exactly nine expected files")


def safe_path(root: Path, name: str) -> Path:
    pure = PurePosixPath(name)
    if (
        pure.is_absolute()
        or str(pure) != name
        or any(part in (".", "..") for part in pure.parts)
        or not re.fullmatch(r"[A-Za-z0-9_./-]+", name)
    ):
        raise ValueError("unsafe manifest path")
    path = root
    for part in pure.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError("symlink in verification or staging path")
    return path


def manifest(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_./-]+)", line)
        if match is None:
            raise ValueError("invalid candidate checksum entry")
        digest, name = match.groups()
        safe_path(Path("."), name)
        if name in result:
            raise ValueError("duplicate candidate checksum path")
        result[name] = digest
    if not result:
        raise ValueError("empty candidate checksum manifest")
    return result


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate candidate buildinfo key")
        result[key] = value
    return result


def validate_candidate(root: Path, repository: Path) -> None:
    inventory(root)
    source_manifest = root / "manifests/SOURCE_SHA256SUMS"
    packaged_manifest = root / "manifests/SHA256SUMS"
    packaged = manifest(packaged_manifest)
    if set(packaged) != LIBRARY_PATHS:
        raise ValueError("packaged manifest must contain exactly four ABIs")
    for abi in ABIS:
        library = root / "jniLibs" / abi / LIBRARY
        if native_symbols.sha256(library) != packaged[f"app/src/main/jniLibs/{abi}/{LIBRARY}"]:
            raise ValueError("packaged library checksum mismatch")
    for name, digest in manifest(source_manifest).items():
        if not name.startswith("tools/arti-build/"):
            raise ValueError("unexpected native source path")
        path = safe_path(repository, name)
        if not path.is_file() or native_symbols.sha256(path) != digest:
            raise ValueError("candidate source does not match the checkout")

    symbols = root / "symbols"
    symbol_checksums = manifest(symbols / native_symbols.CHECKSUMS)
    if set(symbol_checksums) != {native_symbols.BUILDINFO, native_symbols.ARCHIVE}:
        raise ValueError("unexpected symbol checksum inventory")
    for name, digest in symbol_checksums.items():
        if native_symbols.sha256(symbols / name) != digest:
            raise ValueError("symbol artifact checksum mismatch")
    buildinfo = json.loads(
        (symbols / native_symbols.BUILDINFO).read_text(encoding="utf-8"),
        object_pairs_hook=unique_object,
    )
    if not isinstance(buildinfo, dict) or set(buildinfo) != {
        "schemaVersion", "sourceDateEpoch", "sourceManifestSha256",
        "packagedManifestSha256", "libraries",
    }:
        raise ValueError("unexpected candidate buildinfo schema")
    epoch_matches = re.findall(
        r"^NATIVE_SOURCE_DATE_EPOCH=([0-9]+)$",
        safe_path(repository, "tools/arti-build/TOOLCHAIN.env").read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    if (
        type(buildinfo["schemaVersion"]) is not int
        or buildinfo["schemaVersion"] != 1
        or len(epoch_matches) != 1
        or type(buildinfo["sourceDateEpoch"]) is not int
        or buildinfo["sourceDateEpoch"] != int(epoch_matches[0])
        or buildinfo["sourceManifestSha256"] != native_symbols.sha256(source_manifest)
        or buildinfo["packagedManifestSha256"] != native_symbols.sha256(packaged_manifest)
    ):
        raise ValueError("candidate buildinfo is not bound to pinned inputs and manifests")
    libraries = buildinfo["libraries"]
    if not isinstance(libraries, dict) or set(libraries) != set(ABIS):
        raise ValueError("candidate buildinfo must contain exactly four ABIs")
    with zipfile.ZipFile(symbols / native_symbols.ARCHIVE) as archive:
        native_symbols.require_exact_archive_names(archive.namelist())
        with tempfile.TemporaryDirectory(prefix="native-candidate-symbols-") as directory:
            for abi, entry in zip(ABIS, archive.infolist()):
                details = libraries[abi]
                if not isinstance(details, dict) or set(details) != {
                    "buildId", "packagedSha256", "symbolSha256", "symbolSize",
                }:
                    raise ValueError("unexpected library buildinfo schema")
                if (
                    not stat.S_ISREG(entry.external_attr >> 16)
                    or type(details["symbolSize"]) is not int
                    or entry.file_size != details["symbolSize"]
                ):
                    raise ValueError("symbol ZIP entry is not the expected regular file")
                symbol = Path(directory) / abi / LIBRARY
                symbol.parent.mkdir()
                symbol.write_bytes(archive.read(entry))
                actual = native_symbols.inspect_pair(symbol, root / "jniLibs" / abi / LIBRARY)
                if actual != details:
                    raise ValueError("symbol ZIP, buildinfo, and packaged library do not match")


def verify_checkout(repository: Path, expected_sha: str) -> None:
    if repository.is_symlink() or not repository.is_dir():
        raise ValueError("repository must be a directory, not a symlink")
    if not re.fullmatch(r"[0-9a-f]{40}", expected_sha):
        raise ValueError("expected source SHA must be the full workflow GITHUB_SHA")
    actual = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=repository, text=True,
    ).strip()
    if actual != expected_sha:
        raise ValueError("checkout does not match workflow GITHUB_SHA")
    generated = [f":(exclude){path}" for path in sorted(LIBRARY_PATHS)] + [
        f":(exclude)tools/arti-build/{name}" for name in MANIFESTS
    ]
    if subprocess.run(
        ["git", "diff", "--quiet", "HEAD", "--", ".", *generated],
        cwd=repository, check=False,
    ).returncode:
        raise ValueError("tracked source differs from the workflow commit")


def verify_and_stage(first: Path, second: Path, repository: Path, expected_sha: str) -> None:
    if first.resolve() == second.resolve():
        raise ValueError("two distinct candidate directories are required")
    verify_checkout(repository, expected_sha)
    for candidate in (first, second):
        validate_candidate(candidate, repository)
    for name in sorted(FILES):
        if subprocess.run(
            ["cmp", str(first / name), str(second / name)], check=False,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        ).returncode:
            raise ValueError("native candidate bytes differ: " + name)
        print("MATCH " + name)
    verify_checkout(repository, expected_sha)
    outputs = {
        f"jniLibs/{abi}/{LIBRARY}": f"app/src/main/jniLibs/{abi}/{LIBRARY}"
        for abi in ABIS
    } | {f"manifests/{name}": f"tools/arti-build/{name}" for name in MANIFESTS}
    # Preflight all destinations before replacing any output. Atomic replacement
    # avoids writing through an existing regular-file hard link.
    destinations = {name: safe_path(repository, path) for name, path in outputs.items()}
    for name, destination in destinations.items():
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as stream:
            temporary = Path(stream.name)
        try:
            shutil.copyfile(first / name, temporary)
            temporary.chmod(0o644)
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    subprocess.run(
        ["bash", "tools/arti-build/verify-checksums.sh"], cwd=repository, check=True,
    )
    print("Complete candidate replicas match and pass the unchanged native checksum gate.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("first", type=Path)
    parser.add_argument("second", type=Path)
    parser.add_argument("--repository-root", required=True, type=Path)
    parser.add_argument("--expected-sha", required=True)
    args = parser.parse_args()
    verify_and_stage(args.first, args.second, args.repository_root, args.expected_sha)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        raise SystemExit("error: " + str(error)) from error
