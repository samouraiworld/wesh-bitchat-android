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
SOURCE = "5008c95ae99933c180ee2ac6f767dd0e9c203a21"
TAG = "v1.0.0-beta.3-android.107"
RUN = 40197176611
ARTIFACT = 12601010558
ZIP_HASH = "56757fb9aaca3169b344a96b08fb756881db8054b8eb5b5647fe1172c7240440"
APK = "bitchat-android-x86_64-unsigned.apk"
FILES = {
    "BUILDINFO.json": "3b621d41b21f3e3c1b84deeb6badafb5081c8062cd7e2e3bb02cb14b6af61118",
    "NATIVE-SYMBOLS-BUILDINFO.json": "f193d1da01292a2360668c558e2dcd5a8d2c6b9e99ed42239b297a56a250aaa5",
    "NATIVE-SYMBOLS-SHA256SUMS": "a4719ef687ec75244d8b5ec9069434abce71a3c9914e9b8e73cb5c676aef9a7f",
    "SHA256SUMS.unsigned": "bd5a49cd0d50b742bc1f690d65d2cd2fb8c3ce08dd87912d2e5585df751b6b4d",
    "bitchat-android-arm64-unsigned.apk": "fef781869ca00786163b1ff03b72a187f0536e0b017860167767c19959c09553",
    "bitchat-android-armv7-unsigned.apk": "ef9ae696eed8481f1eaa2acbdcf09891e843aa0a8bd97562635dc68aea8881a1",
    "bitchat-android-release-unsigned.aab": "0db93ec973abcb704e05a6d82e4df86c38f0330204ac47d385e6f2c169e02b0c",
    "bitchat-android-universal-unsigned.apk": "f80b004f9571771153dc1b1518cace5c88683eaba8c9e85151793561706cb3de",
    "bitchat-android-x86-unsigned.apk": "d76a59149a0e197b70435e4423e29ed4b3772cc62b9016dfd3bfe16dc4224ea7",
    APK: "414be95a1dbb72bc528ad35fe78849dfd39c17b2aef74ce90d69ee1deb11c500",
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
    signed = root / "wesh107-test-signed.apk"
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
