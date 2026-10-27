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
SOURCE = "753769a07fa9e62c87afead88ea10ef42b823788"
TAG = "v1.0.0-beta.3-android.108"
RUN = 40469284584
ARTIFACT = 12718483430
ZIP_HASH = "24b0145856ec8b05941f790e610adce4aec2a5292b8fd0112064fa05f2e01171"
APK = "bitchat-android-x86_64-unsigned.apk"
FILES = {
    "BUILDINFO.json": "c7ed007f8b4f7add43c241e458140a3efc52fac7b55f2dcad5c81274c0668ad1",
    "NATIVE-SYMBOLS-BUILDINFO.json": "f193d1da01292a2360668c558e2dcd5a8d2c6b9e99ed42239b297a56a250aaa5",
    "NATIVE-SYMBOLS-SHA256SUMS": "a4719ef687ec75244d8b5ec9069434abce71a3c9914e9b8e73cb5c676aef9a7f",
    "SHA256SUMS.unsigned": "1352b029bc574c309c26c38b8599c92c4c382f17462dddeb081889be635b7031",
    "bitchat-android-arm64-unsigned.apk": "ce5dc4a97441bd8d56b0f39b2c5f3bb1b7312a7b63aee8f9172307fcd47c3877",
    "bitchat-android-armv7-unsigned.apk": "368ad573a6ae607e1fcdcf899ecd0fc8a05d46850fff2d5576022147ae472ddb",
    "bitchat-android-release-unsigned.aab": "bebedfa2482da03d30a6fccf3b169369d962e97547477efc0f2a1bb8204139b0",
    "bitchat-android-universal-unsigned.apk": "bdbd6a7e0d0ff3781dd7ac88a4fd9041591cd5b2cf711feeeead5a172f25cde0",
    "bitchat-android-x86-unsigned.apk": "9bcaa7e986ecb276740b0a3d569c3029231d21dea10f2e9ee83643ae5b107334",
    APK: "77fba4f4429517bb94906b5bd26cd23b541c183e49599779ae930ad426896dd0",
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
    signed = root / "wesh108-test-signed.apk"
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
