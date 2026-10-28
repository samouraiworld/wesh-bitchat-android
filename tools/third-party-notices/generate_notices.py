#!/usr/bin/env python3
"""Generate app/src/main/res/raw/third_party_notices.txt.

The notices cover what the release APK ships:
  * the Android libraries on the release runtime classpath, with the licences their Maven POMs
    declare, the licence or notice files packaged in the artifact, and the Apache-2.0 NOTICE
    file of its upstream repository when the artifact does not package it;
  * the fonts in res/raw;
  * the Arti (Tor) native library: every crate of tools/arti-build/Cargo.lock that the Android
    build compiles, found by walking the lock from the JNI wrapper and keeping only the
    dependencies an Android target (or the Linux build host, for build scripts) enables.

Licence texts are always copied from a file: the crate's or artifact's own licence file, the
licence file of the same repository, an upstream file vendored in licenses/, or the SPDX text in
licenses/spdx/. No copyright line is ever written by this script. Inputs are local only; nothing
is downloaded. Any crate the walk reaches but the local cargo cache lacks stops the script.

  python3 -I tools/third-party-notices/generate_notices.py \
    --gradle-deps <output of ./gradlew :app:dependencies --configuration releaseRuntimeClasspath> \
    --gradle-cache "$GRADLE_USER_HOME/caches/modules-2/files-2.1" \
    --cargo-src <CARGO_HOME of the native build>/registry/src/index.crates.io-<hash> \
    --arti-source <Arti checkout at ARTI_COMMIT of tools/arti-build/TOOLCHAIN.env>
"""
import argparse
import hashlib
import io
import os
import re
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LOCK = os.path.join(REPO, "tools", "arti-build", "Cargo.lock")
WRAPPER = os.path.join(REPO, "tools", "arti-build", "Cargo.toml")
TOOLCHAIN = os.path.join(REPO, "tools", "arti-build", "TOOLCHAIN.env")
VENDORED = os.path.join(REPO, "tools", "third-party-notices", "licenses")
OUT = os.path.join(REPO, "app", "src", "main", "res", "raw", "third_party_notices.txt")
FONTS = [
    ("Archivo", "app/src/main/res/raw/archivo_ofl.txt"),
    ("Geist Mono", "app/src/main/res/raw/geist_mono_ofl.txt"),
]
LICENSE_FILE = re.compile(r"(?i)^(licen[cs]e|copying|notice|unlicense|copyright)([-_.].*)?$")

# Upstream licence files that an artifact does not package, copied verbatim into licenses/:
#   no.nordicsemi.android-ble-2.11.0-LICENSE: NordicSemiconductor/Android-BLE-Library, LICENSE at tag 2.11.0
#   libsecp256k1-6e2c8bc4-COPYING: bitcoin-core/secp256k1, COPYING at 6e2c8bc4ecdc6e71dbe7a368f360d8d453ce435d,
#     the submodule commit of ACINQ/secp256k1-kmp v0.24.0
#   spdx/*.txt: spdx/license-list-data v3.27.0, text/<id>.txt
MAVEN_LICENSE_FILES = {"no.nordicsemi.android:ble": "no.nordicsemi.android-ble-2.11.0-LICENSE"}
# Apache-2.0 NOTICE files (section 4(d)) that the upstream repository ships but the artifact does
# not, copied verbatim into licenses/ from the repository at the tag of the version shipped:
#   zxing-3.5.4-NOTICE: zxing/zxing, NOTICE at tag zxing-3.5.4
#   kotlinx.coroutines-1.11.0-NOTICE.txt: Kotlin/kotlinx.coroutines, license/NOTICE.txt at tag 1.11.0
#   kotlinx.serialization-1.7.3-NOTICE.txt: Kotlin/kotlinx.serialization, license/NOTICE.txt at tag v1.7.3
#   kotlinx-atomicfu-0.28.0-NOTICE.txt: Kotlin/kotlinx-atomicfu, license/NOTICE.txt at tag 0.28.0
#   kotlin-2.4.10-NOTICE.txt: JetBrains/kotlin, license/NOTICE.txt at tag v2.4.10
MAVEN_NOTICE_FILES = {
    ("com.google.zxing", "core", "3.5.4"): "zxing-3.5.4-NOTICE",
    **{("org.jetbrains.kotlinx", a, "1.11.0"): "kotlinx.coroutines-1.11.0-NOTICE.txt"
       for a in ("kotlinx-coroutines-core", "kotlinx-coroutines-core-jvm", "kotlinx-coroutines-android")},
    **{("org.jetbrains.kotlinx", a, "1.7.3"): "kotlinx.serialization-1.7.3-NOTICE.txt"
       for a in ("kotlinx-serialization-core", "kotlinx-serialization-core-jvm")},
    **{("org.jetbrains.kotlinx", a, "0.28.0"): "kotlinx-atomicfu-0.28.0-NOTICE.txt" for a in ("atomicfu", "atomicfu-jvm")},
    **{("org.jetbrains.kotlin", a, "2.4.10"): "kotlin-2.4.10-NOTICE.txt"
       for a in ("kotlin-stdlib", "kotlin-stdlib-common", "kotlin-parcelize-runtime")},
}
EMBEDDED = [
    ("libsecp256k1 (native code inside fr.acinq.secp256k1:secp256k1-kmp-jni-android)", "MIT",
     "libsecp256k1-6e2c8bc4-COPYING"),
]
SPDX_TEXTS = {"MIT": "MIT.txt", "Apache-2.0": "Apache-2.0.txt", "MPL-2.0": "MPL-2.0.txt"}
# Arti's own licence files, at the root of its repository.
ARTI_ROOT_TEXTS = {"MIT": ["LICENSE-MIT"], "Apache-2.0": ["LICENSE-APACHE"], "LGPL-3.0-only": ["LGPL-3", "GPL-3"]}

# Targets the native library is built for, and the Linux host that runs build scripts.
_ANDROID = {"target_os": "android", "target_family": "unix", "unix": True, "target_endian": "little",
            "target_env": "", "target_vendor": "unknown", "panic": "abort",
            "target_has_atomic": {"8", "16", "32", "64", "ptr"}}
TARGETS = [
    dict(_ANDROID, triple="aarch64-linux-android", target_arch="aarch64", target_pointer_width="64", target_abi=""),
    dict(_ANDROID, triple="armv7-linux-androideabi", target_arch="arm", target_pointer_width="32", target_abi="eabi"),
    dict(_ANDROID, triple="i686-linux-android", target_arch="x86", target_pointer_width="32", target_abi=""),
    dict(_ANDROID, triple="x86_64-linux-android", target_arch="x86_64", target_pointer_width="64", target_abi=""),
]
HOST = dict(_ANDROID, triple="x86_64-unknown-linux-gnu", target_os="linux", target_env="gnu",
            target_arch="x86_64", target_pointer_width="64", target_abi="", panic="unwind")


def read(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def normalise(text):
    return "\n".join(line.rstrip() for line in text.replace("\r\n", "\n").strip().split("\n")) + "\n"


class Texts:
    """Licence texts, each printed once with every component it applies to."""

    def __init__(self):
        self.order = []
        self.by_key = {}

    def add(self, title, path, component):
        text = normalise(read(path))
        # Copies of one licence often differ only in wrapping or indentation: print one of them.
        key = hashlib.sha256(" ".join(text.split()).encode()).hexdigest()
        if key not in self.by_key:
            self.by_key[key] = {"title": title, "text": text, "components": []}
            self.order.append(key)
        if component not in self.by_key[key]["components"]:
            self.by_key[key]["components"].append(component)

    def add_spdx(self, spdx_id, component):
        self.add(f"SPDX {spdx_id}", os.path.join(VENDORED, "spdx", SPDX_TEXTS[spdx_id]), component)

    def render(self):
        out = []
        for number, key in enumerate(self.order, 1):
            entry = self.by_key[key]
            out += [f"--- Text {number}: {entry['title']} ---", "Applies to: " + ", ".join(entry["components"]), "",
                    entry["text"]]
        return "\n".join(out)


def licence_files(directory):
    return sorted(f for f in os.listdir(directory)
                  if LICENSE_FILE.match(f) and os.path.isfile(os.path.join(directory, f)))


def spdx_ids(expression):
    tokens = re.findall(r"[A-Za-z0-9.+-]+", expression.replace("/", " OR "))
    return [token for token in tokens if token not in ("OR", "AND", "WITH")]


# ---------------------------------------------------------------- cfg() for Cargo target tables

def cfg_holds(spec, target):
    spec = spec.strip()
    if not spec.startswith("cfg("):
        return spec == target["triple"]
    tokens = re.findall(r'"[^"]*"|[A-Za-z_][A-Za-z0-9_]*|[(),=]', spec[4:-1])
    position = 0

    def expression():
        nonlocal position
        name = tokens[position]
        position += 1
        if name in ("all", "any", "not") and position < len(tokens) and tokens[position] == "(":
            position += 1
            values = []
            while tokens[position] != ")":
                values.append(expression())
                if tokens[position] == ",":
                    position += 1
            position += 1
            if name == "all":
                return all(values)
            if name == "any":
                return any(values)
            return not values[0]
        if position < len(tokens) and tokens[position] == "=":
            value = tokens[position + 1].strip('"')
            position += 2
            actual = target.get(name)
            return value in actual if isinstance(actual, set) else actual == value
        return bool(target.get(name, False))  # Unknown flags (custom cfgs, test, ...) are off.

    return expression()


def dependency_kinds(manifest):
    """Package name -> [(kind, cfg)] from every dependency table of a manifest."""
    kinds = {}
    sections = [("normal", "dependencies"), ("build", "build-dependencies"), ("build", "build_dependencies"),
                ("dev", "dev-dependencies"), ("dev", "dev_dependencies")]

    def add(table, kind, cfg):
        for key, value in (table or {}).items():
            name = value.get("package", key) if isinstance(value, dict) else key
            kinds.setdefault(name, []).append((kind, cfg))

    for kind, section in sections:
        add(manifest.get(section), kind, None)
    for cfg, table in (manifest.get("target") or {}).items():
        for kind, section in sections:
            add(table.get(section), kind, cfg)
    return kinds


def edge_enabled(entries):
    for kind, cfg in entries:
        if kind == "dev":
            continue
        targets = TARGETS + [HOST] if kind == "build" else TARGETS
        if cfg is None or any(cfg_holds(cfg, target) for target in targets):
            return True
    return False


# ---------------------------------------------------------------- Rust (Arti native library)

def lock_packages():
    packages = {}
    for block in read(LOCK).split("[[package]]")[1:]:
        name = re.search(r'^name = "([^"]+)"', block, re.M).group(1)
        version = re.search(r'^version = "([^"]+)"', block, re.M).group(1)
        source = re.search(r'^source = "([^"]+)"', block, re.M)
        deps = re.search(r"^dependencies = \[(.*?)\]", block, re.M | re.S)
        packages[(name, version)] = {
            "source": source.group(1) if source else None,
            "deps": re.findall(r'"([^"]+)"', deps.group(1)) if deps else [],
        }
    return packages


def arti_manifests(arti_source):
    found = {}
    for root, dirs, files in os.walk(arti_source):
        dirs[:] = [d for d in dirs if d not in (".git", "target")]
        if "Cargo.toml" in files and root != arti_source:
            manifest = tomllib.loads(read(os.path.join(root, "Cargo.toml")))
            if "package" in manifest:
                found[manifest["package"]["name"]] = root
    return found


def rust_components(args, texts):
    pinned = re.search(r"^ARTI_COMMIT=(\w+)", read(TOOLCHAIN), re.M).group(1)
    actual = subprocess.run(["git", "-C", args.arti_source, "rev-parse", "HEAD"],
                            capture_output=True, text=True, check=True).stdout.strip()
    if actual != pinned:
        sys.exit(f"--arti-source is at {actual}, TOOLCHAIN.env pins {pinned}")
    workspace = tomllib.loads(read(os.path.join(args.arti_source, "Cargo.toml"))).get("workspace", {})
    arti_dirs = arti_manifests(args.arti_source)
    packages = lock_packages()
    by_name = {}
    for name, version in packages:
        by_name.setdefault(name, []).append(version)

    def directory(key):
        name, version = key
        source = packages[key]["source"]
        if name == "arti-android-wrapper":
            return os.path.dirname(WRAPPER)
        if source is None:
            if name not in arti_dirs:
                sys.exit(f"{name} {version} is a path crate missing from --arti-source")
            return arti_dirs[name]
        if not source.startswith("registry+"):
            sys.exit(f"unsupported Cargo source for {name} {version}: {source}")
        path = os.path.join(args.cargo_src, f"{name}-{version}")
        if not os.path.isdir(path):
            sys.exit(f"{name} {version} is built for Android but missing from the cargo cache {args.cargo_src}")
        return path

    def resolve(reference):
        parts = reference.split(" ")
        versions = by_name[parts[0]]
        return parts[0], parts[1] if len(parts) > 1 else versions[0]

    # Walk from the JNI wrapper, following only dependencies an Android build compiles.
    root = ("arti-android-wrapper", by_name["arti-android-wrapper"][0])
    reached, queue = {root}, [root]
    while queue:
        key = queue.pop()
        kinds = dependency_kinds(tomllib.loads(read(os.path.join(directory(key), "Cargo.toml"))))
        for reference in packages[key]["deps"]:
            dep = resolve(reference)
            if dep not in reached and edge_enabled(kinds.get(dep[0], [("normal", None)])):
                reached.add(dep)
                queue.append(dep)

    lines, without_files = [], []
    for key in sorted(reached - {root}):
        name, version = key
        label = f"{name} {version}"
        path = directory(key)
        package = tomllib.loads(read(os.path.join(path, "Cargo.toml")))["package"]
        licence = package.get("license")
        if isinstance(licence, dict) and licence.get("workspace"):
            licence = workspace.get("package", {}).get("license")
        if not licence:
            sys.exit(f"{label} declares no licence")
        lines.append(f"{label} — {licence}" + (" (Arti, The Tor Project)" if packages[key]["source"] is None else ""))
        files = licence_files(path)
        if files:
            for file in files:
                texts.add(file, os.path.join(path, file), label)
            continue
        if packages[key]["source"] is None:
            ids = [i for i in spdx_ids(licence) if i in ARTI_ROOT_TEXTS]
            if not ids:
                sys.exit(f"no Arti licence file for {label} ({licence})")
            for spdx_id in ids:
                for file in ARTI_ROOT_TEXTS[spdx_id]:
                    texts.add(file, os.path.join(args.arti_source, file), label)
            continue
        # The same repository's licence file, from another of its crates in the cache.
        repository = (package.get("repository") or "").rstrip("/").removesuffix(".git").lower()
        siblings = []
        for other in sorted(os.listdir(args.cargo_src)):
            other_path = os.path.join(args.cargo_src, other)
            if other_path == path or not licence_files(other_path):
                continue
            other_package = tomllib.loads(read(os.path.join(other_path, "Cargo.toml"))).get("package", {})
            other_repository = (other_package.get("repository") or "").rstrip("/").removesuffix(".git").lower()
            if repository and other_repository == repository:
                siblings.append((other_package.get("version") != version, other, other_path))
        if siblings:
            _, _, sibling = sorted(siblings)[0]
            for file in licence_files(sibling):
                texts.add(file, os.path.join(sibling, file), label)
            continue
        ids = [i for i in spdx_ids(licence) if i in SPDX_TEXTS]
        if not ids:
            sys.exit(f"{label} ships no licence file and no SPDX text is vendored for {licence}")
        without_files.append(label)
        for spdx_id in ids:
            texts.add_spdx(spdx_id, label)
    return lines, without_files


# ---------------------------------------------------------------- Android libraries (Maven)

def runtime_coordinates(path):
    coordinates = set()
    for line in read(path).splitlines():
        # "group:artifact:version", "group:artifact:version -> resolved" or, for versions a
        # platform sets, "group:artifact -> resolved".
        match = re.search(r"[+\\]--- ([\w.\-]+):([\w.\-]+)(?::([^\s:]+))?(.*)$", line)
        if not match or "(c)" in match.group(4) or "(n)" in match.group(4):
            continue
        group, artifact, version = match.group(1), match.group(2), match.group(3)
        resolved = re.search(r"-> (\S+)", match.group(4))
        if resolved:
            version = resolved.group(1)
        if version is None:
            sys.exit(f"unresolved version in dependency report line: {line}")
        coordinates.add((group, artifact, version))
    return sorted(coordinates)


def find_file(cache, group, artifact, version, suffixes):
    base = os.path.join(cache, group, artifact, version)
    if not os.path.isdir(base):
        return None
    for suffix in suffixes:
        for digest in sorted(os.listdir(base)):
            for name in sorted(os.listdir(os.path.join(base, digest))):
                if name.endswith(suffix) and "-sources" not in name and "-javadoc" not in name:
                    return os.path.join(base, digest, name)
    return None


def pom_licenses(cache, group, artifact, version, depth=0):
    pom = find_file(cache, group, artifact, version, [".pom"])
    if pom is None or depth > 5:
        return []
    root = ET.fromstring(re.sub(r'\sxmlns="[^"]+"', "", read(pom), count=1))
    names = [(lic.findtext("name") or "").strip() for lic in root.findall("./licenses/license")]
    if not names and root.find("parent") is not None:
        parent = root.find("parent")
        names = pom_licenses(cache, parent.findtext("groupId"), parent.findtext("artifactId"),
                             parent.findtext("version"), depth + 1)
    return [n for n in names if n]


def packaged_license_texts(path, scratch):
    """Licence and notice files inside a jar or aar (including the aar's classes.jar)."""
    found = []

    def scan(archive, prefix):
        for item in archive.namelist():
            leaf = item.rsplit("/", 1)[-1]
            if item.endswith(".jar") and "/" not in item:
                scan(zipfile.ZipFile(io.BytesIO(archive.read(item))), prefix + item + "!")
            elif LICENSE_FILE.match(leaf) and not item.endswith("/") and not item.endswith(".class"):
                target = os.path.join(scratch, hashlib.sha256((path + prefix + item).encode()).hexdigest())
                with open(target, "wb") as handle:
                    handle.write(archive.read(item))
                found.append((leaf, target))

    with zipfile.ZipFile(path) as archive:
        scan(archive, "")
    return found


def android_components(args, texts, scratch):
    lines = []
    notice_artifacts = {(group, artifact) for group, artifact, _ in MAVEN_NOTICE_FILES}
    for group, artifact, version in runtime_coordinates(args.gradle_deps):
        label = f"{group}:{artifact} {version}"
        if artifact.endswith("-bom"):
            continue  # A platform: version constraints only, nothing ships.
        notice = MAVEN_NOTICE_FILES.get((group, artifact, version))
        if notice is None and (group, artifact) in notice_artifacts:
            sys.exit(f"{label}: vendor the upstream NOTICE file of this version in licenses/")
        if find_file(args.gradle_cache, group, artifact, version, [".pom"]) is None:
            sys.exit(f"{label} is not in the Gradle cache: build the release once first")
        names = pom_licenses(args.gradle_cache, group, artifact, version)
        licence = " / ".join(names) if names else "see licence text"
        lines.append(f"{label} — {licence}")
        binary = find_file(args.gradle_cache, group, artifact, version, [".aar", ".jar"])
        if binary is None:
            continue  # A multiplatform root: its Android variant is listed with its own files.
        packaged = packaged_license_texts(binary, scratch)
        for leaf, file in packaged:
            texts.add(leaf, file, label)
        vendored = MAVEN_LICENSE_FILES.get(f"{group}:{artifact}")
        if not packaged:
            if vendored:
                texts.add(vendored, os.path.join(VENDORED, vendored), label)
            elif "apache" in " ".join(names).lower():
                texts.add_spdx("Apache-2.0", label)
            else:
                sys.exit(f"no licence file for {label} ({licence}): vendor its upstream file in licenses/")
        if notice:
            texts.add(notice, os.path.join(VENDORED, notice), label)
    for name, licence, file in EMBEDDED:
        lines.append(f"{name} — {licence}")
        texts.add(file, os.path.join(VENDORED, file), name)
    return lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gradle-deps", required=True)
    parser.add_argument("--gradle-cache", required=True)
    parser.add_argument("--cargo-src", required=True)
    parser.add_argument("--arti-source", required=True)
    parser.add_argument("--scratch", default=os.environ.get("TMPDIR", "/tmp"))
    parser.add_argument("--out", default=OUT)
    args = parser.parse_args()

    texts = Texts()
    scratch = os.path.join(args.scratch, "wesh-notices")
    os.makedirs(scratch, exist_ok=True)
    android = android_components(args, texts, scratch)
    fonts = []
    for name, path in FONTS:
        fonts.append(f"{name} — SIL Open Font License 1.1")
        texts.add(os.path.basename(path), os.path.join(REPO, path), name)
    rust, without_files = rust_components(args, texts)
    lock_hash = hashlib.sha256(open(LOCK, "rb").read()).hexdigest()

    body = [
        "Wesh — third-party notices",
        "",
        "Wesh is free software under the GNU General Public License version 3. It is built on",
        "BitChat for Android and includes the components listed below, each under its own licence.",
        "The full licence texts follow the lists.",
        "",
        f"Generated by tools/third-party-notices/generate_notices.py (Cargo.lock sha256 {lock_hash}).",
        "",
        "== Wesh and BitChat ==",
        "bitchat-android — GPL-3.0 (https://github.com/permissionlesstech/bitchat-android)",
        "",
        f"== Android libraries ({len(android)}) ==",
        *android,
        "",
        "== Fonts ==",
        *fonts,
        "",
        f"== Arti, the Tor implementation, and the Rust components of its native library ({len(rust)}) ==",
        "Source of every component: https://crates.io and https://gitlab.torproject.org/tpo/core/arti",
        *rust,
        "",
        "== Rust components that ship no licence file ==",
        "Their published package has no licence file; the SPDX text of their declared licence applies.",
        *without_files,
        "",
        "== Licence texts ==",
        "",
        texts.render(),
    ]
    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write("\n".join(body))
    print(f"{args.out}: {len(android)} Android libraries, {len(rust)} Rust components "
          f"({len(without_files)} without licence file), {len(texts.order)} distinct texts, "
          f"{os.path.getsize(args.out)} bytes")


if __name__ == "__main__":
    main()
