# Canonical Android 109 FGS video attempt

This is a disposable, synthetic **emulator demonstration**, not physical Mesh Lab
qualification and not a claim of Google Play approval. No application, transport,
runtime data, production key or release build configuration is changed.

The `FGS canonical 109 emulator evidence` workflow validates the scripts on PRs.
Its expensive job runs only when a reviewer deliberately adds
`fgs-emulator-reviewed` to this repository's PR after reviewing its exact head.
Do not remove/re-add the label or rerun automatically. A further attempt requires
review of the specific failed gate and its exact corrected head. The job has a
25-minute limit and the UI flow a 270-second
scenario budget. On success both host physical-display recordings finish their
300-second limit, preserving a short final post-roll; failure never exports a passing recording.
Any missing real state aborts; there is no fake-peer fallback.

The distinct label `fgs-emulator-diagnostic-reviewed` is default-off and requires
a new, exact-head human authorization of failure-media export before use. Only
that label sets the closed value `FGS_DIAGNOSTIC_FAILURE_MEDIA=true`. If both
host recorders started, both regular WebMs finish, the allowlisted primary gate
is preserved and reader cleanup is complete, an incomplete attempt may retain
the two synthetic WebMs with `attempt.json`, `provenance.json` and `SHA256SUMS`
for seven days. Otherwise it retains receipts only. The ordinary
`fgs-emulator-reviewed` job never sets this flag and still exports no WebM on
failure. Unknown flag values fail closed. An exported diagnostic failure remains
`NOT_COMPLETED`, keeps its primary gate and `finalVideoQualified=false`; the
local assembler rejects it. If the exact same strict scenario instead completes
all nine events with valid WebMs and clean teardown, enabling failure retention
does not invalidate that genuine success: it remains subject to the usual local
duration, codec and visual review. This option exists only to make a separately
authorized failure visually diagnosable and is never enabled by static CI.

The six attempts documented below are retained as **historical canonical 107**
diagnostics. Each one-attempt approval was consumed by its named run. The current
tooling is rebound to canonical 109 and has no inherited approval: rebasing,
validation or review does not authorize a label, capture, recording or upload.
The canonical 108 attempts documented below are historical as well; their
one-attempt approvals are consumed and do not carry over to Android 109.

## Separate non-media onboarding check

The exact label `fgs-onboarding-reviewed` enables a separate 25-minute job only
after its exact PR head passes the static validation job. It checks out that head,
reuses `prepare.py` to verify canonical Android 109, all eleven attestations,
the unchanged APK payload and a disposable test signature, then starts one pinned
API 35 Google APIs x86_64 revision 9 AVD. The existing real UI controller installs
that test-signed canonical APK, preserves its generated synthetic nickname, taps
the enabled Start action, handles actual permission prompts and requires the real
`Public room` home state.

This job does not call either emulator or guest recording, assemble media, send
messages, assert peer discovery or foreground-service behavior, or upload any
artifact. SDK, AVD and emulator output remains in the owned temporary directory;
the only controller output is one allowlisted `PASS` or `FAIL` line with a gate
and elapsed seconds. An `always()` cleanup removes the disposable signature, AVD,
SDK logs and temporary data. Completion qualifies only the canonical first-launch
onboarding path on that synthetic CI image. It is not physical evidence, FGS
evidence, store media or permission for a later capture.

The onboarding, full-scenario, read-only diagnostic and video jobs compare four
distinct label names by exact equality. Adding `fgs-onboarding-reviewed` cannot
activate any other job; tests enforce that separation.

`fgs-scenario-reviewed` is a fourth exact, manually applied label for a separate
two-AVD non-media check. It verifies the same canonical Android 109 APK and runs
the shared real product flow—first onboarding, foreground services, real peer
discovery, bidirectional public messages, background notification, background
delivery, resume and Quit—inside the 270-second scenario budget. Its
zero point is named `scenario_started`; it never emits the media-only
`recording_commands_started` event. The job has no recorder, assembler, artifact
upload or `GITHUB_OUTPUT`, and always removes its disposable APK, keys, AVDs and
SDK logs. A PASS qualifies only this synthetic two-AVD non-media scenario; it is
not physical-device, Play-signing, video or public-release evidence.

The consumed non-media scenario run `40509022819` reached the disposable
two-AVD controller and ended at `ui_dump_output_missing_a` after 107.0 seconds.
Its closed trace proves that two side-A dump attempts reached the output check
without a nonempty hierarchy file; because the same bounded trace was shared by
both onboarding threads, it does not attribute the recorded home observation to
side A. No raw stderr or XML was retained, so the run cannot distinguish the
platform dumper's documented idle-timeout and null-root branches. It produced no
artifact and does not qualify the product scenario.

The separately reviewed non-media scenario builds a
small one-shot DEX reader from `ui-reader/UiHierarchyReader.java`. It compiles
against the installed API 35 `android.jar` and converts with the repository's
pinned Android build tools 37.0.0 (`gradle/libs.versions.toml`), without Maven,
Gradle dependencies, instrumentation APKs or product changes. The shell process
loads that DEX together with `/system/framework/uiautomator.jar`, reflects only
public constructors and methods on the platform `UiAutomationShellWrapper` and
`AccessibilityNodeInfoDumper`, disables compressed layout hierarchy, then calls
public `UiAutomation.getRootInActiveWindow()` directly. It never calls
`waitForIdle`, changes `FLAG_SECURE`, takes a screenshot, uses root, grants a
permission or enables a hidden-API exemption.

Each disposable side receives a nonce-scoped controller-owned reader path and
XML path. The controller checks the reader SHA-256 after push, healthchecks a
fresh, complete `hierarchy` containing at least one `node` before the first app
launch, and then makes every snapshot use that reader with no silent fallback to
classic `uiautomator dump`. Root-null, bridge, API, dump, output, disconnect,
checksum and cleanup failures map to fixed gates; raw output, XML, paths and
exceptions remain unexported. Disconnect runs in `finally`, and the controller
removes and proves absence of the guest reader during its existing always-cleanup
path. The same two retries and 13/26-second command/snapshot bounds remain
unchanged; the overall scenario clock is 270 seconds. This is
an empirical compatibility check for the pinned API 35 Google APIs image: the
reflected classes are platform `@hide` classes even though the invoked members
are public, so static compilation alone is not a runtime qualification.

The first run with that reader, `40513813866`, compiled it on the disposable
runner and passed both pre-launch launcher healthchecks. After each real app
launch, however, both sides returned a null active root; the final gate was
`ui_reader_root_null_a`. No Welcome, onboarding or Quit milestone was qualified.
Cleanup passed and the run exported zero artifacts. A platform
connection does not itself guarantee that an active-window root is immediately
available, so the reader now admits further root reads for four monotonic seconds
inside the same connected bridge, sleeping at most 100 milliseconds between
reads. It never starts a new read after that admission deadline. An individual
platform root query can itself block for up to five seconds; therefore four
seconds is not claimed as a wall-clock bound, and the unchanged 13-second host
command timeout remains the hard limit. The reader still never calls
`waitForIdle`, and an exhausted poll still returns the same closed root-null gate.

The closed non-media trace is schema version 2. Every retained record now carries
only `side=a`, `side=b` or `side=global`; it never uses a serial as an identifier.
The existing 64-record head/tail bound, aggregate counts, finite timings and
fixed event enum remain unchanged. Canonical 109 non-media run `40545072286`
completed the whole real two-AVD software scenario with gate `none`, including
bidirectional and background messages, notification resume and Quit; cleanup
passed and it exported no artifact. This validates the direct reader on the
pinned disposable API 35 image for that non-media scenario only. It is not
physical-device, video, Play-signing or public-release evidence.

The media controller now builds the same API 35/build-tools 37 reader, checks
its hash and healthchecks both disposable sides before recorder capability,
host recording and the first product launch. Every later media snapshot therefore
uses the configured direct reader with no classic-dump fallback. Reader cleanup
is fail-closed without masking an earlier scenario or recorder gate. This parity
change does not alter `FLAG_SECURE`, product actions, the 13/26-second UI bounds,
the two-WebM closed inventory or seven-day retention. The evidence-based overall
scenario and host-recorder limits are now 270/300 seconds as documented below.
It has not run and does not itself authorize or qualify a recording.

The consumed canonical 109 media run `40551799481` used the direct reader and
started both host recorders, then ended at `onboarding_unknown_a`. Its receipt
contains only the recorder-start event: the media path at that reviewed head did
not pass the closed observer into the shared product scenario, so it cannot
identify the last screen class on either side. The timing is compatible with the
side-A onboarding sub-deadline being exhausted, but does not establish a screen,
reader failure, product defect or recorder-contention cause. No WebM was exported.
The media controller now passes the same bounded observer as the successful
non-media controller and embeds only its validated enum/side/timing/count payload
in `attempt.json` on success or failure. This closes an observability gap only;
it does not change a selector, product action, gate, retry, timeout, recorder,
`FLAG_SECURE`, or video success criterion, and it does not authorize another run.

The first non-media run, `40496918526`, verified canonical preparation and then
ended after 168.1 seconds at `ui_dump_command_timeout_a`. Cleanup passed, the
other two jobs were skipped and the run produced no artifact. The elapsed time
is consistent with the onboarding caller reaching its full 120-second deadline;
the aggregate gate does not establish which product screen was visible or that
UIAutomator's normal 13-second command cap was itself insufficient. This run
therefore does not qualify onboarding and does not justify raising timeouts.

For a separately reviewed future non-media run, the controller now emits a
second bounded `ONBOARDING_TRACE` line after its existing status line. It contains
at most 64 events from a fixed enum, aggregate counters and only non-negative
finite allocated/elapsed timings. Progress enums distinguish activity resume,
Welcome observation, Start action, permission categories and home; UI enums
distinguish the dump phases. No UI label, node, XML, path, emulator serial or raw
command output reaches the trace. The media and non-media paths share this trace
codec; the media receipt embeds it without changing the closed failure/success
file inventories. Both paths require the ordered real Welcome, Start
and home observations before onboarding can pass, so a transient home shell is
not accepted; Start and permission actions must also resolve to an actionable
button/node rather than an enabled heading. If a dump command expires only
because the onboarding caller deadline was already reached when that failure
was observed, the controller preserves its last onboarding phase; an earlier
dump timeout remains a strict dump failure even if retry delay later crosses the
caller deadline.

The consumed media run `40501825753` reached real onboarding, foreground-service
and peer-discovery milestones, then ended at `ui_dump_command_timeout_a` before
the bidirectional-message milestone. It exported no WebM and therefore provided
no video to review or qualify. The shared scenario now requires actionable
composer/submit/confirmation controls, scopes the confirmation CTA to its dialog,
and accepts send/receive completion only when the synthetic message is an exact
component of a canonical-app bubble semantic, outside editors (including their
ancestors/descendants), dialog prompts and System UI. Remote reads also require the app
to be foreground, including an explicit resumed-activity check after opening the
foreground-service notification. The optional closed trace records only fixed
phase enums and finite timings; its 64-event bound preserves the first eight
records plus a rolling tail of 56 so late post-peer timings remain diagnostic,
while locked aggregate counters remain complete across both onboarding threads.

The non-media run `40515608335` reached real onboarding, foreground service,
peer discovery, both public rooms and the first confirmation action, then ended
with the aggregate gate `ui_dump_command_timeout_a` before any exact local or
remote message bubble was observed. Its total controller duration does not prove
that the 165-second scenario deadline, rather than a narrower caller deadline,
was reached. The controller now verifies the exact synthetic draft in one
canonical-app editor before requesting Send, refuses empty or inverted action
bounds, and distinguishes a confirmation tap request from subsequently observing
the dialog still present or absent. Send and receive snapshots feed the same
closed trace with fixed composer, confirmation, allowlisted feedback and exact
bubble-candidate enums only. A `failure_caller_deadline_reached` event means only
that the failing UI snapshot reported its own caller deadline reached; it neither
renames the failure gate nor asserts that the global scenario budget expired.
An allowlisted feedback already visible before Send remains diagnostic and does
not block the request; only a newly observed feedback after the request can
classify a refusal, and it never overrides an exact canonical message bubble.
The fixed unconfirmed-send warning is classified separately as
`feedback_send_unconfirmed`: it fails the attempt while preserving the original
`message_submit` gate and is never treated as permission to tap Send again. The
warning explicitly describes a possibly delivered message, so the controller
cannot safely infer that no transport handoff occurred.
No UI text, message, bounds, node, XML or identifier is added to the trace, and
success still requires exact canonical-app bubbles on both emulators.

The next non-media run, `40519086123`, ended at `onboarding_unknown_a` before
the send diagnostics ran. Sixty snapshots completed and the retained tail shows
repeated unknown observations on side A, but the aggregate Welcome, Start and
home counters do not identify which side produced them. No raw hierarchy was
retained, so the receipt cannot distinguish Wesh's intentionally identical
CHECKING/INITIALIZING home shell from startup, loading, error, permission or
other unrecognized screens, and the total controller duration proves none of
those causes.

The closed trace now classifies onboarding snapshots in memory from exact known
markers and fixed package families. Its enum distinguishes ready and premature
home, enabled and disabled Welcome markers, the Bluetooth and notifications
explainers, Android permission UI, startup preparing/blocked, app loading/error,
Bluetooth fallback, Android error controls, and unknown app/system/foreign/mixed
screens. Repeated identical
screens are suppressed; the last class is emitted once more on failure so it
survives the bounded rolling tail. Consequently trace counters count emitted
diagnostic records, not screen visits. No label or package value is serialized,
no new action is taken, and only the ordered real Welcome, Start and home still
passes onboarding. On a later send/receive snapshot failure, the already closed
composer, bubble, feedback and confirmation states are likewise re-emitted
before the fixed failure event; the original gate and all time budgets remain
unchanged.

The first canonical 109 non-media run, `40541974677`, completed both real
onboardings, foreground-service checks, peer discovery and both public-room
open actions. It then reached neither Send request nor message bubble. Its
closed trace recorded a single Wesh editor with a nonempty value different from
the expected synthetic message and eventually ended at the caller deadline;
recent snapshots completed, while the final command exhausted its remaining
sub-second allowance. This alone does not establish a hung reader. The retained
enums do not reveal the draft value and cannot prove which character transition
failed.

The controller previously tapped the Compose editor and immediately issued its
single `input text` command without observing that the editor had actually
gained focus. A red baseline test demonstrated that an explicitly unfocused
editor still received that command. The controller now waits, under the existing
deadline, for exactly one canonical-app editor with `focused=true` before its
one text injection. It never clears, presses Back, retaps or retries. The fixed
trace records `composer_focus_requested` and `composer_focused`, and classifies
a nonempty mismatch only as shorter, longer or same-length-other; it never
exports the value. A terminal snapshot failure re-emits those closed states so
they survive the rolling tail. This correction does not change a reader, gate,
budget, success criterion or media authorization.

The consumed standard media run `40555857933` completed both real onboardings,
foreground-service checks, peer discovery and both public-room opens. Its single
bulk `input text` command returned successfully after the canonical editor was
focused, but the closed trace then observed only a shorter draft on side A. No
exact draft, Send request, confirmation or message bubble occurred; all 40 UI
snapshots completed and the controller eventually ended at `message_composer_a`.
The receipt does not reveal the draft and cannot establish whether Android input,
Compose handling or persistence omitted a character.

The controller now uses short, distinct synthetic tokens `A109`, `B109` and
`C109` and types them once from left to right. Each character is injected exactly
once; the next character is not requested until one unique focused Wesh editor
equals the exact expected prefix. Seeing the unchanged previous prefix may wait
under the existing deadline because UI admission is asynchronous. Any unrelated,
longer, ambiguous or unfocused value fails closed without clear, Back, retap,
repair or retry, and Send remains impossible until the complete token is exact.
Only fixed `composer_character_requested` and `composer_prefix_confirmed` events
are added to the trace; no character, prefix, length or UI value is exported.
This is a controlled robustness experiment that removes the bulk-injection
assumption. It does not establish the cause of the prior shorter draft and did
not change the app, transport, gates or then-current 165-second budget.

The next standard media run, `40558394251`, passed canonical preparation and all
eleven attestations, then exited in the two-AVD runner step before `capture.py`
could create an attempt receipt. Its closed artifact contained only
`provenance.json`; it therefore proves no AVD, recorder, video or product state.
The raw job log was deliberately not retrieved, so the exact prerequisite that
failed remains unknown. The reviewed emulator and image pins and all runner
resource thresholds remain unchanged.

Runner setup now maps its resource, SDK revision, reader build, acceleration and
per-side AVD-creation checks to fixed allowlisted prerequisite gates. A guarded
error trap covers an otherwise unexpected pre-controller exit without recording
the failed command or its output. If `capture.py` has not already created a real
attempt, the same strict evidence directory receives one exclusive synthetic
`attempt.json` beside `provenance.json`; no path, resource value, log, XML, key,
APK, AVD data or media is added. Existing attempt receipts are never overwritten.
The ordinary standard job keeps diagnostic failure-media retention disabled;
the distinct diagnostic label and its explicit authorization requirement are
unchanged. These receipts improve classification only and do not establish the
cause of run `40558394251` or qualify any product behavior.

The later standard media run `40560604149` reached real onboarding, foreground
services and peer discovery, but the product returned its fixed `NOT_SENT`
feedback after the first confirmed public-send action. The draft stayed present
and no message bubble was accepted. That outcome is retained as an actual product
refusal; the controller does not retry it, and no timing change reclassifies or
claims to fix its still-undetermined internal cause.

Run `40563487786` then established a separate timing fact. Both real public sends
and their opposite-side receipts completed by 135.081 seconds, and the connected
device notification was visible by 144.835 seconds. During the required third
background message, the closed trace reached eleven aggregate character requests
and ten confirmed prefixes before a reader allocation of 0.046 seconds exhausted
the 165-second caller clock at 164.888 seconds. Cleanup was complete and no WebM
was exported. This proves neither product failure nor video success: the original
165/180-second window was an earlier controller choice and no longer covered the
expanded serialized UI reads plus the still-required remote receipt, resume,
Quit, stopped service and removed notification.

The controlled timing configuration therefore uses a 270-second scenario clock
and a 300-second host-recorder limit. All nine real events remain mandatory and
strictly ordered, including the third remote receipt, notification resume, real
Quit, service stop and notification absence. The 13/26-second UI bounds,
90-second onboarding and peer bounds, retry counts, controller timeout and
25-minute job limit are unchanged. This is only a timing trial justified by the
closed receipt. The upstream recorder parser permits this limit, but the pinned
emulator's 300-second behavior remains runtime-unqualified until its terminal
start response, decoded duration and full Quit coverage pass the existing
fail-closed checks. This does not weaken a gate, retry `NOT_SENT`, change the
release app or `FLAG_SECURE`, or qualify footage before the full scenario and
local media review pass.

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
are unchanged.

The fourth attempt, [40218805154](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40218805154),
passed canonical preparation and entered the AVD/capture controller, then stopped
before both onboardings completed. Its allowlisted receipt was `NOT_COMPLETED`
with no events or recordings. The old controller intentionally erased raw AVD/UI
logs but also collapsed every non-IME failure into one message, so the precise
boot/install/UI sub-gate cannot be recovered and no BLE conclusion can be drawn.
The corrected controller exports only named phase/side failures, retries transient
UI dumps inside hard deadlines, waits for Bluetooth ON, installation, resumed
activities, real home, FGS and real peer discovery. It never exports UI XML,
logcat, host paths or exception text. One further attempt required review of its
exact head and a deliberate Lead label; the following history records each
single reviewed attempt, and no automatic rerun is permitted.

The fifth approved host-display attempt,
[40456733798](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40456733798),
used reviewed controller source `e2162cf8bff50b8cc065be83c1528e378620ff9c`.
Canonical preparation passed and both owned emulators reached the
`boot_install_bluetooth_ready` preflight event at 59.2 seconds. The controller
then stopped during side A onboarding with the allowlisted aggregate gate
`ui_dump_a`; it exported no scenario events or recordings. That one-attempt
approval is consumed, so this history does not authorize a label, rerun or new
capture.

The exact subcause of `ui_dump_a` cannot be recovered from that privacy-minimal
receipt: the old aggregate covered a dump timeout/nonzero result, a read
timeout/nonzero result, and XML parse failure, while raw UI output was correctly
not retained. A controller defect is independently proven: its entire retry
window was six seconds, but Android's `uiautomator dump` implementation may wait
up to ten seconds for UI idle before reading the hierarchy. The corrected
snapshot reader has a 26-second cap beneath its caller's deadline, at most two
dump commands capped at 13 seconds each, reads only within the remaining budget,
and uses a fresh side-specific controller-owned path for each attempt. It removes
that path before and after use and exports only allowlisted timeout, command,
freshness, missing-output, read, parse or cleanup gates. It never exports XML or
exception text. This fixes the proven six-versus-ten-second mismatch; it does
**not** prove that mismatch was the cause of run 40456733798, nor establish any
BLE or host-recorder result.

The sixth attempt,
[40458661485](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40458661485),
used reviewed controller source `90647c126a2f88f0e4b8be57e094bc60c55f6c85`.
It reached boot/install/Bluetooth readiness at 58.1 seconds, completed both real
onboardings at 106.4, confirmed both foreground services at 106.8 and displayed
the real mutual netsim peer at 109.9. It then stopped in the pre-recording Quit
stage with `ui_dump_cleanup_a`; no scenario event or recording was exported.
That gate does not identify whether the strict preparatory removal (two-second
cap) or the post-read removal (one-second cap) failed, nor whether adb timed out
or returned an error. The old `finally` could also mask a successful snapshot or
another active UI gate. The privacy-minimal receipt therefore proves neither the
exact cleanup subcause nor side B's final state. Its one-attempt approval is
consumed.

The current reader keeps a strict controller-owned preparatory removal and
nonexistence check for every unique side-specific path. Post-read removal is now
best-effort for up to three seconds and cannot replace the functional result or
its primary failure gate. Only unresolved counts for sides `a` and `b` may enter
the receipt; owned paths stay in process memory for a bounded final sweep. XML,
paths, stderr and exception text never enter evidence. The workflow uploads an
exact receipt allowlist and always destroys the disposable AVD directory, so a
deferred remote file cannot become an artifact.

The first reviewed canonical 108 attempt,
[40478621327](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40478621327),
used controller source `60eb972a0c3e7fbd358f77e9c5bff18bb72c8323`.
Canonical preparation passed and both owned emulators reached
`boot_install_bluetooth_ready` at 48.2 seconds. The controller then stopped at
`host_recorder_capability_a`, before first launch, onboarding or any scenario
event; no WebM was exported. Its privacy-minimal receipt cannot distinguish a
console transport failure from a help-contract rejection. Offline inspection of
the pinned emulator package found the documented host recorder, so this result
does not establish an unsupported runner. It did reveal a controller mismatch:
the old check asked parent-command help for a WebM detail documented on the
`screenrecord start` subcommand.

The corrected preflight asks the documented `help-verbose` command and separates
an adb/timeout failure (`host_recorder_help_transport_<side>`) from a malformed,
missing-command, `KO` or non-terminal-`OK` response
(`host_recorder_help_contract_<side>`), without exporting console text. At that
historical head, the real recording command was
`screenrecord start --time-limit 180 <owned>.webm` and required a terminal `OK`
with no `KO` line; there was no alternate recorder or guest-side fallback. The
approval for run 40478621327 was consumed. This code
correction does not authorize another run: any new attempt requires fresh
explicit approval for its exact reviewed source head.

The next reviewed canonical 108 attempt,
[40483231939](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40483231939),
used controller source `80ba457fea80a4d29f1498a4557da18bc91ab536`.
Canonical preparation passed, both owned emulators reached
`boot_install_bluetooth_ready` at 68.6 seconds and both host recording commands
started. Side A then stopped at the old aggregate gate `onboarding_welcome_a`,
before any later scenario event; no WebM was exported. The privacy-minimal
receipt cannot distinguish a nickname-field action, shell input, keyboard
dismissal, Start action or the welcome deadline, so it does not establish one
runtime cause. The same welcome strings and controller routine had completed
both onboardings in historical run 40458661485. This attempt's approval is
consumed and does not authorize a retry.

Canonical 109 retains the Android `FLAG_SECURE` source policy of the historical
canonical 108 and 107 builds for the real `MainActivity`. The release-boundary
receipt verifies that source policy is unchanged; its runtime behavior has not
yet been exercised on canonical 109.
Guest-side `adb shell screenrecord` is therefore not an acceptable capture path:
it can complete while masking the product pixels. The controller now has no
guest-recorder fallback. It requires the official Android Emulator console
recorder (`adb -s <serial> emu screenrecord`), which Android documents as recording
the emulated physical display directly from the host into WebM. Verbose-help
transport, help structure and each recording `OK` response are fail-closed.
Whether emulator 37.2.12 actually includes
the secure product pixels in this host recording remains an empirical gate; a
black or masked frame is failure, never evidence.

On a fresh Welcome screen the product itself generates a valid synthetic
nickname and enables the real Start action. The controller therefore retains
that product-generated nickname: it no longer focuses, clears or types into the
field, queries input-method state or sends Back. It taps only the real enabled
Start node. An action failure is reported as
`onboarding_welcome_start_<side>` and remaining on Welcome until the caller's
deadline as `onboarding_welcome_timeout_<side>`. This removes unnecessary input
operations; it is not represented as the proven cause of run 40483231939. No
preference, permission, identity or keyboard configuration is seeded or forced.
Failure receipts add only `failureElapsedSeconds`, a finite nonnegative attempt
clock; successful receipts require it to be null. No UI contents or raw error
details accompany it.

The fixed canonical artifact is `12749229098` from release run `40536454475`,
source `e2855f8c79827dd4693b2d9e6ab8c603400d045b`, tag
`v1.0.0-beta.3-android.109` (annotated, **not Git-signed**). Its artifact ZIP
SHA-256 is
`ddc85f6ab4b5cb03c88bfb2b4a324c06c1e84951c8d5169322767ba4adc2ce97`.
Its ZIP and all eleven
file hashes are pinned, its eleven provenance attestations require the exact
repository/workflow/source/ref and reject self-hosted builders. The x86_64 APK is
signed with a disposable test key without rebuilding; every ZIP entry must remain
byte-identical. This is explicitly not the Play signing identity.
The x86_64 release manifest reports package `org.samouraiworld.wesh.android`,
version `1.0.0` code `109` and the `connectedDevice` foreground-service type;
all five release APKs exclude capture fixtures. The four native libraries are
byte-identical to canonical 108 and match the packaged source. These are release
binding facts, not physical-device, runtime-FGS or video qualification.

Use the public Ubuntu 24.04 x64 hosted runner class with KVM: 4 vCPU, 16 GB RAM,
14 GB disk. A preflight requires 12 GiB free before download, then 11 GiB before
SDK/AVD creation and 10 GiB available RAM. No developer workstation AVD is used.
Two API35 Google APIs x86_64 revision9 AVDs share default netsim, with separate
userdata, ports 5554/5556, 2 GiB RAM and two virtual CPUs per AVD. Emulator
37.2.12 is required; changed SDK revisions fail instead of silently drifting.
All adb operations specify exactly one of the two created emulator serials.
No GPS/location provider, real phone, account, camera, QR, logcat or private data
is accessed. Internet mode remains at its product default OFF.

Preflight ends after both fresh AVDs boot, enable Bluetooth and install the
unchanged-payload APK; it does not launch Wesh. Host-recorder capability is then
checked and both host-side physical-display recordings must return `OK` before
the first app launch. The zero-clock event is therefore
`recording_commands_started`, not a claim that onboarding is already complete.

Actual first launch, product onboarding and permission dialogs are then operated
in parallel through UIAutomator hierarchy plus scoped OS input, with a 90-second
deadline clamped to the single 270-second recording clock. Product-generated
synthetic nicknames are retained and synthetic messages are typed normally.
Completion of both onboardings, both real
foreground services and mutual netsim discovery are recorded at their real,
positive times. No preference, permission, identity or peer is seeded. This
captures the in-app setup and connection before interaction rather than
presenting an already-connected state as evidence. Each app must then receive the
opposite app's public message. One app moves to background, its actual connected
device FGS notification is shown, another real message is sent, the notification
opens the app, and the message must be visible. This demonstrates sending while
the other app is in background and visibility after resume, not the exact instant
of background receipt. Finally the real **Quit Wesh** action must again stop the
service and remove its notification. The controller finds the Wesh notification
inside SystemUI's `notification_stack_scroller`, scopes it to the matching
`expandableNotificationRow`, expands only that row through Android's
`expand_button` when its scoped Quit action is not already present, and accepts
**Quit Wesh** only inside the same row. After the
service stops it opens the shade again and requires a fresh stack
snapshot with no matching Wesh notification; a collapsed or unavailable shade is
not accepted as absence. Closed trace enums identify each Quit sub-phase without
exporting notification text, hierarchy XML, identifiers or paths. The scenario
budget is 270 seconds; both OS
recorders retain their fixed 300-second limit for strict coverage and post-roll.

## Onboarding-only validation before any further recording

A reviewed local non-capture validation uses one fresh isolated arm64 AVD and
temporary user-data directory. It hash-checks and disposable-signs the canonical
arm64 APK, verifies that signing did not change its payload and does not rebuild
the app. It then exercises only the first-launch Start action and real permission
flow. It starts no host or guest recorder. Its closed local result contains only
the reviewed source identifier, an allowlisted gate and numeric timings; UI XML,
logs, APK, key, AVD data and frames remain unexported and the temporary AVD is
destroyed.

That narrow run could validate the changed Welcome controller path, but not the
CI image, netsim/BLE, foreground service, `FLAG_SECURE` visibility or final
video. Local execution is covered by the autonomous validation mandate after
review of its exact helper. A pass is a prerequisite for considering another
video attempt, not authorization for one. Any new GitHub media capture/export
still requires fresh explicit approval tied to the exact reviewed source head.

With a future specific approval for canonical 109, only after the genuine scenario and both host
recorders succeed may CI export `recording-a.webm` and `recording-b.webm`,
provenance, an allowlisted event receipt
and checksums as a seven-day Actions artifact. Historical canonical 107
one-attempt approvals for source `e2162cf8bff50b8cc065be83c1528e378620ff9c` and source
`90647c126a2f88f0e4b8be57e094bc60c55f6c85` were consumed respectively by failed
runs 40456733798 and 40458661485. Neither authorizes another label, run, capture
or upload.
Any future attempt requires new explicit approval for the exact reviewed source
head, two fresh synthetic owned AVDs and seven-day GitHub Actions retention.
Approval of an earlier attempt does not carry over. A successful result would be
`SCENARIO_PASS_RECORDINGS_AWAIT_LOCAL_VALIDATION`, never qualified-video PASS.
The receipt identifies the path as
`captureSource=ANDROID_EMULATOR_HOST_PHYSICAL_DISPLAY_WEBM`.
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
wrong controller source, incomplete manifests or changed inputs. It requires the
single boot preflight event, `recording_commands_started` at zero, and all eight
real product events at positive monotone times below 270 seconds. Deferred UI
cleanup diagnostics must contain exactly nonnegative integer counts for sides
`a` and `b`, never paths or XML. Both local
`ffprobe` durations must strictly cover the last Quit event from the conservative
clock started before either recorder command. The current CLI keeps the explicit
`--font` input and requires Pillow in the local Python environment. Pillow renders
a deterministic footer PNG outside the product-screen pixels; `ffmpeg` fully
decodes and combines the two actual screens, overlays that PNG, and strips
metadata. The footer explicitly identifies two emulators, simulated BLE netsim,
canonical109 payload and disposable test signing.
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
- [Android Emulator host screen recording](https://developer.android.com/studio/run/emulator-record-screen)
- [Android Emulator console screenrecord command](https://developer.android.com/studio/run/emulator-console)
- [Google Bumble: shared netsim radio controllers](https://google.github.io/bumble/platforms/android.html)
- [GitHub hosted runner resources and Android acceleration](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
- [Official FGS declaration/video requirements](https://support.google.com/googleplay/android-developer/answer/13392821)

Offline checks: `python3 -m unittest discover -s tools/fgs-evidence -p 'test_*.py'`
and `bash -n tools/fgs-evidence/run.sh`. These do not exercise BLE or prove a video.
