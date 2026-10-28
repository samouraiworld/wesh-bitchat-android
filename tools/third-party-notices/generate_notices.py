#!/usr/bin/env python3
"""Generate app/src/main/res/raw/third_party_notices.txt.

The notices cover what the release APK ships:
  * the Android libraries on the release runtime classpath, with the licences their Maven POMs
    declare and any licence or notice file packaged in the artifact;
  * the fonts in res/font;
  * the Arti (Tor) native library: every Arti crate and every crates.io crate of
    tools/arti-build/Cargo.lock whose sources the native build extracted, with the licence
    files those crates ship.

Inputs are local only; nothing is downloaded.

  python3 -I tools/third-party-notices/generate_notices.py \
    --gradle-deps <output of ./gradlew :app:dependencies --configuration releaseRuntimeClasspath> \
    --gradle-cache "$GRADLE_USER_HOME/caches/modules-2/files-2.1" \
    --cargo-src <CARGO_HOME of the native build>/registry/src/index.crates.io-<hash> \
    --arti-source <Arti checkout at the tag in tools/arti-build/TOOLCHAIN.env>
"""
import argparse
import hashlib
import io
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LOCK = os.path.join(REPO, "tools", "arti-build", "Cargo.lock")
OUT = os.path.join(REPO, "app", "src", "main", "res", "raw", "third_party_notices.txt")
FONTS = [
    ("Archivo", "app/src/main/res/raw/archivo_ofl.txt"),
    ("Geist Mono", "app/src/main/res/raw/geist_mono_ofl.txt"),
]
LICENSE_FILE = re.compile(r"(?i)^(licen[cs]e|copying|notice|unlicense|copyright)([-_.].*)?$")
MIT_TEMPLATE = """Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""


BSD3_TEMPLATE = """Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software without
   specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
"""
# Copyright holders of BSD-licensed artifacts whose POM and archive do not name one.
BSD_HOLDERS = {
    "no.nordicsemi.android:ble": "Nordic Semiconductor ASA",
}
# Native code bundled inside an artifact, under its own licence.
EMBEDDED = [
    ("libsecp256k1 (native code inside fr.acinq.secp256k1:secp256k1-kmp-jni-android)", "MIT",
     "Copyright (c) 2013 Pieter Wuille"),
]


def read(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def normalise(text):
    return "\n".join(line.rstrip() for line in text.replace("\r\n", "\n").strip().split("\n")) + "\n"


class Texts:
    """Licence texts, each printed once with every component it applies to."""

    def __init__(self):
        self.order = []
        self.by_hash = {}

    def add(self, title, text, component):
        text = normalise(text)
        # Copies of one licence often differ only in wrapping or indentation: print one of them.
        key = hashlib.sha256(" ".join(text.split()).encode()).hexdigest()
        if key not in self.by_hash:
            self.by_hash[key] = {"title": title, "text": text, "components": []}
            self.order.append(key)
        if component not in self.by_hash[key]["components"]:
            self.by_hash[key]["components"].append(component)

    def render(self):
        out = []
        for number, key in enumerate(self.order, 1):
            entry = self.by_hash[key]
            out.append(f"--- Text {number}: {entry['title']} ---")
            out.append("Applies to: " + ", ".join(entry["components"]))
            out.append("")
            out.append(entry["text"])
        return "\n".join(out)


# ---------------------------------------------------------------- Rust (Arti native library)

def lock_packages():
    packages = []
    for block in read(LOCK).split("[[package]]")[1:]:
        name = re.search(r'^name = "([^"]+)"', block, re.M).group(1)
        version = re.search(r'^version = "([^"]+)"', block, re.M).group(1)
        source = re.search(r'^source = "([^"]+)"', block, re.M)
        packages.append((name, version, source.group(1) if source else None))
    return packages


def crate_license(manifest):
    match = re.search(r'^license\s*=\s*"([^"]+)"', manifest, re.M)
    return match.group(1).replace("/", " OR ").replace("  ", " ") if match else "see licence file"


def crate_authors(manifest):
    match = re.search(r"^authors\s*=\s*\[(.*?)\]", manifest, re.M | re.S)
    names = re.findall(r'"([^"]+)"', match.group(1)) if match else []
    return ", ".join(re.sub(r"\s*<[^>]*>", "", n) for n in names) or "the authors"


def rust_components(args, texts):
    arti_mit = read(os.path.join(args.arti_source, "LICENSE-MIT"))
    arti_apache = read(os.path.join(args.arti_source, "LICENSE-APACHE"))
    lines = []
    for name, version, source in sorted(lock_packages()):
        label = f"{name} {version}"
        if source is None:
            if name == "arti-android-wrapper":
                continue  # Wesh's own JNI wrapper, GPL-3.0 with the app.
            lines.append(f"{label} — MIT OR Apache-2.0 (Arti, The Tor Project)")
            texts.add("MIT licence (Arti)", arti_mit, label)
            texts.add("Apache License 2.0", arti_apache, label)
            continue
        if not source.startswith("registry+"):
            sys.exit(f"unsupported Cargo source for {label}: {source}")
        directory = os.path.join(args.cargo_src, f"{name}-{version}")
        if not os.path.isdir(directory):
            continue  # Not built for Android: cargo never extracted it.
        manifest = read(os.path.join(directory, "Cargo.toml"))
        spdx = crate_license(manifest)
        lines.append(f"{label} — {spdx}")
        files = sorted(f for f in os.listdir(directory)
                       if LICENSE_FILE.match(f) and os.path.isfile(os.path.join(directory, f)))
        for file in files:
            texts.add(f"{file}", read(os.path.join(directory, file)), label)
        if not files:
            if "Apache-2.0" in spdx:
                texts.add("Apache License 2.0", arti_apache, label)
            if "MIT" in spdx:
                texts.add(f"MIT licence ({name})",
                          f"Copyright (c) {crate_authors(manifest)}\n\n{MIT_TEMPLATE}", label)
            if "MPL-2.0" in spdx:
                # Same canonical text as the MPL-2.0 crate that ships it (option-ext).
                texts.add("LICENSE.txt", read(os.path.join(args.cargo_src, "option-ext-0.2.0", "LICENSE.txt")), label)
            if not any(known in spdx for known in ("Apache-2.0", "MIT", "MPL-2.0")):
                sys.exit(f"no licence file and no known licence text for {label} ({spdx})")
    return lines


# ---------------------------------------------------------------- Android libraries (Maven)

def runtime_coordinates(path):
    coordinates = set()
    for line in read(path).splitlines():
        match = re.search(r"[+\\]--- ([\w.\-]+):([\w.\-]+):(\S+)(.*)$", line)
        if not match or "(c)" in match.group(4) or "(n)" in match.group(4):
            continue
        group, artifact, version = match.group(1), match.group(2), match.group(3)
        resolved = re.search(r"-> (\S+)", match.group(4))
        if resolved:
            version = resolved.group(1)
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
        return [], None
    root = ET.fromstring(re.sub(r'\sxmlns="[^"]+"', "", read(pom), count=1))
    names = [(lic.findtext("name") or "").strip() for lic in root.findall("./licenses/license")]
    organisation = (root.findtext("./organization/name") or "").strip() or None
    if not names and root.find("parent") is not None:
        parent = root.find("parent")
        names, inherited = pom_licenses(cache, parent.findtext("groupId"), parent.findtext("artifactId"),
                                        parent.findtext("version"), depth + 1)
        organisation = organisation or inherited
    return [n for n in names if n], organisation


def packaged_license_texts(path):
    """Licence and notice files inside a jar or aar (including the aar's classes.jar)."""
    found = []

    def scan(archive):
        for item in archive.namelist():
            leaf = item.rsplit("/", 1)[-1]
            if item.endswith(".jar") and "/" not in item:
                scan(zipfile.ZipFile(io.BytesIO(archive.read(item))))
            elif LICENSE_FILE.match(leaf) and not item.endswith("/") and not item.endswith(".class"):
                found.append((item, archive.read(item).decode("utf-8", "replace")))

    with zipfile.ZipFile(path) as archive:
        scan(archive)
    return found


def android_components(args, texts, apache):
    lines = []
    for group, artifact, version in runtime_coordinates(args.gradle_deps):
        label = f"{group}:{artifact} {version}"
        if artifact.endswith("-bom"):
            continue  # A platform: version constraints only, nothing ships.
        if find_file(args.gradle_cache, group, artifact, version, [".pom"]) is None:
            sys.exit(f"{label} is not in the Gradle cache: build the release once first")
        names, organisation = pom_licenses(args.gradle_cache, group, artifact, version)
        licence = " / ".join(names) if names else "see licence text"
        lines.append(f"{label} — {licence}")
        binary = find_file(args.gradle_cache, group, artifact, version, [".aar", ".jar"])
        if binary is None:
            continue  # A multiplatform root: its Android variant is listed with its own files.
        packaged = packaged_license_texts(binary)
        for item, text in packaged:
            texts.add(item.rsplit("/", 1)[-1], text, label)
        if packaged:
            continue
        joined = " ".join(names).lower()
        if "apache" in joined:
            texts.add("Apache License 2.0", apache, label)
        if "bsd" in joined:
            holder = BSD_HOLDERS.get(f"{group}:{artifact}") or organisation
            if holder is None:
                sys.exit(f"name the BSD copyright holder of {label} in BSD_HOLDERS")
            texts.add(f"BSD 3-Clause licence ({holder})", f"Copyright (c) {holder}\n\n{BSD3_TEMPLATE}", label)
        if "apache" not in joined and "bsd" not in joined:
            sys.exit(f"no licence text for {label} ({licence})")
    for name, licence, copyright_line in EMBEDDED:
        lines.append(f"{name} — {licence}")
        texts.add(f"MIT licence ({name.split(' ')[0]})", f"{copyright_line}\n\n{MIT_TEMPLATE}", name)
    return lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gradle-deps", required=True)
    parser.add_argument("--gradle-cache", required=True)
    parser.add_argument("--cargo-src", required=True)
    parser.add_argument("--arti-source", required=True)
    args = parser.parse_args()

    texts = Texts()
    apache = read(os.path.join(args.arti_source, "LICENSE-APACHE"))
    android = android_components(args, texts, apache)
    fonts = []
    for name, path in FONTS:
        fonts.append(f"{name} — SIL Open Font License 1.1")
        texts.add("SIL Open Font License 1.1", read(os.path.join(REPO, path)), name)
    rust = rust_components(args, texts)
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
        "== Licence texts ==",
        "",
        texts.render(),
    ]
    with open(OUT, "w", encoding="utf-8") as handle:
        handle.write("\n".join(body))
    print(f"{OUT}: {len(android)} Android libraries, {len(rust)} Rust components, "
          f"{len(texts.order)} distinct texts, {os.path.getsize(OUT)} bytes")


if __name__ == "__main__":
    main()
