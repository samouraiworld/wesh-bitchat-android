#!/usr/bin/env python3
"""Read only a fixed allowlist of disposable CI prerequisites; no APK, SDK install or AVD."""
import json
import os
from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as E


def installed_revision(path):
    try:
        revision = E.parse(path).getroot().find("localPackage/revision")
        return [int(revision.findtext(part, "0")) for part in ("major", "minor", "micro")]
    except (OSError, E.ParseError, AttributeError, ValueError):
        return None


def missing_preconditions(commands, kvm_exists, disk_gib, memory_gib, cpus):
    missing = ["sdkmanager_missing"] if not commands["sdkmanager"] else []
    if not kvm_exists:
        missing.append("kvm_missing")
    if disk_gib < 11:
        missing.append("disk_below_11_gib")
    if memory_gib < 10:
        missing.append("available_memory_below_10_gib")
    if cpus < 4:
        missing.append("cpu_count_below_4")
    return missing


def main(output):
    if os.environ.get("GITHUB_ACTIONS") != "true" or sys.platform != "linux":
        sys.exit("Diagnostic requires the disposable GitHub Linux runner")
    head = os.environ.get("EVIDENCE_HEAD", "")
    if re.fullmatch(r"[0-9a-f]{40}", head) is None:
        sys.exit("Diagnostic requires an exact reviewed source SHA")
    sdk_value = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    sdk = Path(sdk_value) if sdk_value else None
    sdk_dirs = [sdk/relative for relative in ("emulator", "platform-tools", "cmdline-tools/latest/bin")] if sdk else []
    search_path = os.pathsep.join([*(str(p) for p in sdk_dirs), os.environ.get("PATH", "")])
    commands = {name: shutil.which(name, path=search_path) is not None
                for name in ("sdkmanager", "avdmanager", "emulator", "adb", "keytool", "sudo")}
    memory_kib = next(int(line.split()[1]) for line in Path("/proc/meminfo").read_text().splitlines()
                      if line.startswith("MemAvailable:"))
    memory_gib = memory_kib / 1024**2
    free_gib = shutil.disk_usage(output.parent).free / 1024**3
    cpus = os.cpu_count() or 0
    kvm = Path("/dev/kvm")
    result = {
        "purpose": "READ_ONLY_RUNNER_CAPABILITY_DIAGNOSTIC",
        "source": head,
        "doesNotReconstructThePreviousRunner": True,
        "apkDownloaded": False, "signingPerformed": False, "sdkInstalled": False,
        "avdLaunched": False, "bleTested": False, "videoRecorded": False,
        "commandPresent": commands,
        "kvmExists": kvm.exists(),
        "kvmReadableByRunner": os.access(kvm, os.R_OK),
        "kvmWritableByRunner": os.access(kvm, os.W_OK),
        "cpuCount": cpus, "availableMemoryGiB": round(memory_gib, 2),
        "freeDiskGiB": round(free_gib, 2),
        "installedEmulatorRevision": installed_revision(sdk/"emulator/package.xml") if sdk else None,
        "installedApi35ImageRevision": installed_revision(sdk/"system-images/android-35/google_apis/x86_64/package.xml") if sdk else None,
        "missingExistingPreconditions": missing_preconditions(commands, kvm.exists(), free_gib, memory_gib, cpus),
    }
    # No paths, executable locations, identifiers, host inventory, environment dump or raw logs.
    with output.open("x") as stream:
        os.chmod(output, 0o600)
        json.dump(result, stream, indent=2)
        stream.write("\n")
    print(json.dumps(result))


if __name__ == "__main__":
    try:
        main(Path(sys.argv[1]))
    except Exception:
        sys.exit("Capability diagnostic failed before a safe receipt; no raw output exported")
