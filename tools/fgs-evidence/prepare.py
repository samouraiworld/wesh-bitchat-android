#!/usr/bin/env python3
"""Verify fixed public release inputs; never rebuild or use a production key."""
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import zipfile
import shutil

REPO = "samouraiworld/wesh-bitchat-android"
SOURCE = "e2855f8c79827dd4693b2d9e6ab8c603400d045b"
TAG = "v1.0.0-beta.3-android.109"
RUN = 40536454475
ARTIFACT = 12749229098
ZIP_HASH = "ddc85f6ab4b5cb03c88bfb2b4a324c06c1e84951c8d5169322767ba4adc2ce97"
APK = "bitchat-android-x86_64-unsigned.apk"
FILES = {
    "BUILDINFO.json": "3b28ae49b720a08f183ad9db11f0f2ba81a32d0a033d29f051bb8301775055c2",
    "NATIVE-SYMBOLS-BUILDINFO.json": "f193d1da01292a2360668c558e2dcd5a8d2c6b9e99ed42239b297a56a250aaa5",
    "NATIVE-SYMBOLS-SHA256SUMS": "a4719ef687ec75244d8b5ec9069434abce71a3c9914e9b8e73cb5c676aef9a7f",
    "SHA256SUMS.unsigned": "2be448211b8059a0730c7b8a61a93f795a2ce27e4078914db1e9523ac583c07b",
    "bitchat-android-arm64-unsigned.apk": "3a45e56f42a9998cc9bf7734a8d83115c07dcc7f4f1b19c9b0a07de53f14fed7",
    "bitchat-android-armv7-unsigned.apk": "10235e36962d3eaa48c5893f78dff668dd3bc592213c44b952b6e126c42c579e",
    "bitchat-android-release-unsigned.aab": "f1350c10fd869dd99828d05c0a72660ef0f170fb9bb6a9649bfb0ef408d4946a",
    "bitchat-android-universal-unsigned.apk": "00dedc0db660ed13d6d4ceeb3b809640b9ba9e49feea9ad5b140634c0e7baac0",
    "bitchat-android-x86-unsigned.apk": "08b0093fd2c1ca90876999cca87018a3a3550933036dad24fda456e18fadf3b3",
    APK: "70f996f28165860db3c0de385d9fbcf97588b22e30742356c07267d5c91baa67",
    "wesh-android-native-debug-symbols.zip": "bdcb64e04c3cc92f65eab91af2758e6a8c87b411d354b5f1658f76cfe14a9410",
}


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(*args, **kwargs):
    # Keep raw CLI output on the disposable runner, out of Actions logs/artifacts.
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=180, **kwargs)
    if result.returncode:
        raise RuntimeError(f"Required command failed: {args[0]}")
    return result.stdout


def verify_archive(path, expected=FILES):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) != set(expected):
            raise ValueError("Unexpected or duplicate canonical ZIP entries")
        for info in archive.infolist():
            if stat.S_ISLNK(info.external_attr >> 16) or "/" in info.filename:
                raise ValueError("Unsafe canonical ZIP entry")
            with archive.open(info) as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != expected[info.filename]:
                    raise ValueError("Canonical file checksum mismatch")


def verify_payload(before, after):
    # V2/V3 signing adds a signing block, not ZIP entries. Every entry must remain identical.
    with zipfile.ZipFile(before) as a, zipfile.ZipFile(after) as b:
        if len(a.namelist()) != len(set(a.namelist())) or len(b.namelist()) != len(set(b.namelist())):
            raise ValueError("Duplicate APK entries")
        if set(a.namelist()) != set(b.namelist()):
            raise ValueError("Signing changed APK entry inventory")
        for name in a.namelist():
            if a.read(name) != b.read(name):
                raise ValueError("Signing changed APK payload")


def main(root):
    if os.environ.get("GITHUB_ACTIONS") != "true" or sys.platform != "linux":
        raise ValueError("Disposable GitHub Linux runner required")
    if shutil.disk_usage(root.parent).free < 12 * 1024**3:
        raise ValueError("At least 12 GiB free space required")
    root.mkdir(parents=True, exist_ok=False)
    os.chmod(root, 0o700)
    canonical = root / "canonical"
    canonical.mkdir()
    meta = json.loads(run("gh", "api", f"repos/{REPO}/actions/artifacts/{ARTIFACT}"))
    if (meta["expired"] or meta["name"] != "verified-unsigned-release" or
            meta["workflow_run"]["id"] != RUN or meta["workflow_run"]["head_sha"] != SOURCE or
            meta["digest"] != f"sha256:{ZIP_HASH}"):
        raise ValueError("Artifact identity mismatch")
    zipped = root / "canonical.zip"
    zipped.write_bytes(run("gh", "api", f"repos/{REPO}/actions/artifacts/{ARTIFACT}/zip"))
    if digest(zipped) != ZIP_HASH:
        raise ValueError("Artifact ZIP checksum mismatch")
    verify_archive(zipped)
    with zipfile.ZipFile(zipped) as archive:
        archive.extractall(canonical)  # Exact flat inventory and symlinks already checked.
    for name in FILES:
        run("gh", "attestation", "verify", str(canonical / name), "--repo", REPO,
            "--signer-workflow", f"{REPO}/.github/workflows/release.yml",
            "--source-digest", SOURCE, "--source-ref", f"refs/tags/{TAG}",
            "--deny-self-hosted-runners", "--format", "json")
    build = json.loads((canonical / "BUILDINFO.json").read_text())
    if build["sourceCommit"] != SOURCE:
        raise ValueError("BUILDINFO source mismatch")
    # SDK tools installation is isolated from the application; no Gradle build is run.
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", os.environ["ANDROID_HOME"]))
    run(str(sdk / "cmdline-tools/latest/bin/sdkmanager"), "build-tools;37.0.0")
    tools = sdk / "build-tools/37.0.0"
    password = os.urandom(24).hex()
    env = {**os.environ, "FGS_TEST_PASSWORD": password}
    key = root / "disposable-test.p12"
    run("keytool", "-genkeypair", "-keystore", str(key), "-storetype", "PKCS12",
        "-alias", "synthetic-test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
        "-dname", "CN=Synthetic emulator test", "-storepass:env", "FGS_TEST_PASSWORD", env=env)
    signed = root / "wesh109-test-signed.apk"
    run(str(tools / "apksigner"), "sign", "--ks", str(key), "--ks-pass", "env:FGS_TEST_PASSWORD",
        "--v1-signing-enabled", "false", "--v4-signing-enabled", "false", "--out", str(signed),
        str(canonical / APK), env=env)
    run(str(tools / "apksigner"), "verify", "--verbose", str(signed))
    verify_payload(canonical / APK, signed)
    key.unlink()
    receipt = {"source": SOURCE, "tag": TAG, "gitTagSigned": False, "releaseRun": RUN,
               "artifact": ARTIFACT, "artifactZipSHA256": ZIP_HASH,
               "unsignedApkSHA256": FILES[APK], "testSignedApkSHA256": digest(signed),
               "attestationsVerified": 11, "apkPayloadUnchanged": True,
               "signature": "Disposable test key, not Play signing", "physicalQualification": False}
    evidence = root / "evidence"
    evidence.mkdir()
    (evidence / "provenance.json").write_text(json.dumps(receipt, indent=2) + "\n")
    # Free download space before booting AVDs; only the installable test APK remains.
    shutil.rmtree(canonical)
    zipped.unlink()


if __name__ == "__main__":
    try:
        main(Path(sys.argv[1]))
    except Exception:
        sys.exit("Canonical verification/signing failed; no emulator attempt started.")
