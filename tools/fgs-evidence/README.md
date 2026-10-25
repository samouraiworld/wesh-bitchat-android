# Canonical Android 107 FGS video attempt

This is a disposable, synthetic **emulator demonstration**, not physical Mesh Lab
qualification and not a claim of Google Play approval. No application, transport,
runtime data, production key or release build configuration is changed.

The `FGS canonical 107 emulator evidence` workflow validates the scripts on PRs.
Its expensive job runs only when a reviewer deliberately adds
`fgs-emulator-reviewed` to this repository's PR after reviewing its exact head.
Do not remove/re-add the label or rerun automatically. A further attempt requires
review of the specific failed gate and its exact corrected head. The job has a
25-minute limit and the UI flow a 165-second
scenario budget. On success both real OS recordings finish their 180-second
limit, preserving a short final post-roll; on failure they stop immediately.
Any missing real state aborts; there is no fake-peer fallback.

The first reviewed attempt, [40214942196](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40214942196),
failed during Java setup: `21.0.11+10` was not a matching Temurin setup selector.
Canonical verification, signing, SDK/AVD setup, BLE and recording did not run;
no evidence artifact was produced. This failure remains historical. The corrected
tool-only selector `21.0.11+10.0.LTS` matches `android-build.yml`, demonstrated by
successful existing run [40202327656](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40202327656).
It does not change or rebuild the canonical APK. A second attempt may start only
after its exact correction is reviewed and the Lead deliberately labels it again.

The second attempt, [40215355034](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40215355034),
passed Java setup, canonical verification, eleven attestations, disposable signing
and unchanged-payload validation. Its AVD preparation step failed without a
sub-gate diagnostic. The only artifact was `provenance.json`; no boot, BLE exchange
or video is demonstrated. Do not infer a Bluetooth failure from this result.

A separate `fgs-emulator-diagnose` label enables a **read-only diagnostic job**
after exact-head review. It reports only targeted command-presence booleans,
KVM access, CPU/RAM/disk numbers, installed SDK revisions and named missing
preconditions. It does not install/download SDKs or APKs, sign, launch AVDs,
contact peers, record video or change KVM permissions. No developer machine is
inspected, and no path, host identifier, environment dump or raw log is exported.
Its green completion means only that the diagnostic ran; capability failures stay
explicit in the JSON. It cannot start the expensive evidence job. A subsequent
video attempt requires its own reviewed correction and deliberate label event.

The read-only [diagnostic 40216447434](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40216447434)
on source `e60cbbb234401163f0cd98581f77427ac700dff2` found `ffmpeg` and `ffprobe`
absent on that runner. SDK Manager, KVM, CPU/RAM/disk and the caption font were
present; KVM still needed the existing permission step, and the emulator/API35
image were not installed. This describes that diagnostic runner, not a
reconstruction of either failed attempt. No APK, SDK installation, AVD or BLE was
run by the diagnostic.

The third attempt, [40217333354](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40217333354),
failed at `ubuntu_ffmpeg_install`, before canonical preparation and AVD setup;
no evidence artifact or BLE result was produced. The APT installation step has
now been removed. CI requires no `ffmpeg`, `ffprobe`, media package installation
or media parser. SDK/image pins, KVM handling and all real scenario assertions
are unchanged. A fourth attempt requires review of the exact corrected head and
a deliberate Lead label, with the same 25-minute job limit.

Nickname entry dismisses the keyboard only when the owned emulator's actual
input-method state reports `mInputShown=true`. False sends no Back; a missing or
ambiguous field aborts with `ime_visibility_unknown`, without navigating Welcome
blindly. The raw state stays in memory; no preference, identity or keyboard
configuration is seeded or forced.

The fixed canonical artifact is `12601010558` from release run `40197176611`,
source `5008c95ae99933c180ee2ac6f767dd0e9c203a21`, tag
`v1.0.0-beta.3-android.107` (annotated, **not Git-signed**). Its ZIP and all eleven
file hashes are pinned, its eleven provenance attestations require the exact
repository/workflow/source/ref and reject self-hosted builders. The x86_64 APK is
signed with a disposable test key without rebuilding; every ZIP entry must remain
byte-identical. This is explicitly not the Play signing identity.

Use the public Ubuntu 24.04 x64 hosted runner class with KVM: 4 vCPU, 16 GB RAM,
14 GB disk. A preflight requires 12 GiB free before download, then 11 GiB before
SDK/AVD creation and 10 GiB available RAM. No developer workstation AVD is used.
Two API35 Google APIs x86_64 revision9 AVDs share default netsim, with separate
userdata, ports 5554/5556, 2 GiB RAM and two virtual CPUs per AVD. Emulator
37.2.12 is required; changed SDK revisions fail instead of silently drifting.
All adb operations specify exactly one of the two created emulator serials.
No GPS/location provider, real phone, account, camera, QR, logcat or private data
is accessed. Internet mode remains at its product default OFF.

Actual product onboarding and permission dialogs are operated through UIAutomator
hierarchy plus scoped OS input. Synthetic nicknames/messages are typed normally.
Both apps must display one real peer and each must receive the opposite app's
public message. Then one app moves to background, its actual FGS notification is
shown, another real message is sent, the notification opens the app, and the
message must be visible. This demonstrates sending while the other app is in
background and visibility after resume, not the exact time of background receipt.
The real notification's **Quit Wesh** action must stop
the service and remove its notification.

Only after the genuine scenario and both OS recorder processes succeed may CI
export `recording-a.mp4` and `recording-b.mp4`, provenance, an allowlisted event
receipt and checksums as a seven-day Actions artifact. This explicit permission
covers only these two fresh, synthetic owned-AVD OS recordings. Its result is
`SCENARIO_PASS_RECORDINGS_AWAIT_LOCAL_VALIDATION`, never qualified-video PASS.
Failure exports receipts only; emulator/SDK logs, UI XML, keys, APKs and AVD
userdata are never uploaded. The raw recordings are not physical-device evidence
or Google-approved footage. Missing files stop export; playback, truncation,
duration and final visual inspection remain mandatory local gates.

After independently verifying the Actions ZIP digest, exact file inventory and
source, use the existing trusted **local** media tools with `localassemble.py`:

```sh
python3 tools/fgs-evidence/localassemble.py --evidence <verified-evidence-directory> \
  --source-head <reviewed-controller-sha> --ffmpeg <trusted-ffmpeg-binary> \
  --ffprobe <trusted-ffprobe-binary> --font <local-font-file> --output <new-local-output.mp4>
```

The assembler refuses CI execution, extra files/symlinks/duplicate JSON keys,
wrong controller source, incomplete manifests or changed inputs. Both local
`ffprobe` durations must strictly cover the last Quit event from the conservative
clock started before either recorder command. `ffmpeg` fully decodes and combines
the actual screens, strips metadata, and adds the explicit footer identifying
two emulators, simulated BLE netsim, canonical107 payload and test signing.
The assembled duration is checked again before giving the output its final name.
The local receipt remains `DURATION_AND_ASSEMBLY_PASS_AWAIT_VISUAL_REVIEW`;
the Lead must inspect actual footage before any Play use. Tool/font paths are
explicit inputs and are not recorded in receipts. Nothing is uploaded or
submitted by this local script. No YouTube/public publication occurs.

The unproven compatibility boundary is the exact image's multiple-advertising and
GATT support for this APK; generic BLE support alone does not prove Wesh works.
If genuine discovery or receipt fails, stop and report that outcome. Do not swap
in beacons, Compose fixtures, debug hooks or invented connection indicators.

Primary references:

- [Android emulator BLE support](https://developer.android.com/studio/run/emulator-networking)
- [Google Bumble: shared netsim radio controllers](https://google.github.io/bumble/platforms/android.html)
- [GitHub hosted runner resources and Android acceleration](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
- [Official FGS declaration/video requirements](https://support.google.com/googleplay/android-developer/answer/13392821)

Offline checks: `python3 -m unittest discover -s tools/fgs-evidence -p 'test_*.py'`
and `bash -n tools/fgs-evidence/run.sh`. These do not exercise BLE or prove a video.
