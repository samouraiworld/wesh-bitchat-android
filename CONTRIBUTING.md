# Contributing to the Wesh Android validation fork

Read [AGENTS.md](AGENTS.md) first. Work on a feature branch and submit a pull
request. Preserve other contributors' changes. Keep raw logs, APKs, signing
material, device identifiers, contacts, messages, full QR payloads and location
data out of Git. Use synthetic fixtures and repository-relative paths.

## Build inputs

Use JDK 21, the SDK and Build Tools versions pinned in
`gradle/libs.versions.toml`, and the checked-in Gradle wrapper. Keep caches and
raw evidence in ignored local directories. SDK licenses are an operator action.

The app ID is `org.samouraiworld.wesh.android`; it isolates this experiment from
an upstream BitChat installation. Keep the existing debug signer when testing
upgrades. Never commit a keystore or create distribution keys as part of a
routine validation build.

## Native dependencies

The four tracked Arti `.so` files are required vendored build inputs. Their
source, wrapper, dependency lock and toolchain are pinned in `tools/arti-build`.
Follow [the native rebuild guide](tools/arti-build/README.md). The supported
builder captures read-only copies of its inputs before compilation, refuses
unsupported patch inputs, checks every required JNI export and all 64-bit LOAD
alignments, scans for host paths and publishes source/library checksum manifests.

```sh
python3 tools/arti-build/test-provenance.py
ARTI_SYMBOL_OUTPUT_DIR=.reproducible-build/native-symbols-first \
  tools/arti-build/rebuild-in-container.sh
ARTI_SYMBOL_OUTPUT_DIR=.reproducible-build/native-symbols-second \
  tools/arti-build/rebuild-in-container.sh
tools/arti-build/compare-symbols.sh \
  .reproducible-build/native-symbols-first \
  .reproducible-build/native-symbols-second
tools/arti-build/verify-checksums.sh
```

For a dependency update, the two distinct output directories must start empty.
Their byte-identical symbol archive and build information prove that all four
packaged-library hashes and all four unstripped libraries reproduced. Bind the
reviewed archive to the canonical unsigned AAB with `native_symbols.py
verify-release` as documented in the native rebuild guide.
A changed lock or wrapper with stale libraries must fail the `preBuild` gate.
Never edit the checksum manifests to make an old library appear rebuilt.
Raw download caches may be reused; compiled target objects must not be reused
for the clean comparison.

The native audit still reports RSA RUSTSEC-2023-0071 and unmaintained bincode /
paste warnings. A patched rustls lock does not establish runtime reachability,
remove RSA, or authorize distribution. Do not add advisory ignores or change
cryptographic algorithms to silence this gate.

## Android checks

```sh
./gradlew :app:assembleDebug testDebugUnitTest
./gradlew lintDebug
```

Record exact source revision, candidate version, command result, test totals and
skips. Lint is a strict gate: keep `abortOnError = true` and
`checkReleaseBuilds = true`, compare diagnostics with the checked baseline and
inspect every changed-line issue. The current baseline contains 12 historical
warnings; do not expand it or disable checks to hide new findings.

English and French are the maintained product locales. Keep exact resource-key
parity between the base `values` files and `values-fr`, validate every
localized format-argument contract, and keep `locales_config.xml` limited to
`en` and `fr` unless a separately reviewed translation adds a complete locale.
Both active lint output and the baseline must remain at zero
`MissingTranslation` and zero `MissingQuantity`. Keep localization changes
separate from routing or cryptographic behavior.

Keep binary verification parsing unsigned and bounded before copying bytes.
Reject malformed sizes, unexpected tags and trailing fields. Preserve the
existing signed preimage and shared synthetic QR vector across platforms.
Source-level wire review and a real verifier API test do not establish a live
cross-client scan or physical session. Keep test fixtures out of production
source sets; inspect packaged marker matches in context instead of claiming
that any short byte-pattern collision is an embedded secret.

Check APK package/version/label and debug signer independently from the native
checksums. Record native ZIP compression modes and verify alignment with the
pinned SDK `zipalign -c -P 16` when libraries are stored uncompressed. ELF LOAD
alignment, APK ZIP alignment and real 16 KiB device behavior are different
checks. Keep artifact paths, hashes and provenance concordant.

## Secure startup and lifecycle

Wesh prepares identity storage and cryptography on an IO dispatcher through the
process-owned `startup/WeshRuntime` coordinator. Keep Activity construction and
chat / notification / QR operations gated on readiness; retries must use the
same durable identity. Keep Bluetooth / unified construction on Main with the
prepared crypto argument rather than recreating keystore objects there.

An absent first-use key and an unreadable, partial or incorrectly sized key are
different states. Never replace the latter with a new identity. Identity writes
must check the Boolean result of `commit`; the KTX `edit(commit = true)` helper
returns Unit and does not provide that result. Keep the process-wide failed-write
and panic-wipe fences in `DurableIdentityPreferences`: Android can change its
in-memory map even when commit reports failure. Do not silence the resulting
style-only UseKtx diagnostics or weaken the persistence checks.

Foreground-service promotion must precede suspension for secure startup; an
ineligible restart stops before waiting or starting transport. Stop/quit must
cancel its waiter. Preserve only the latest bounded, allowlisted pending route
in Activity state and reapply existing QR and per-send authorization at use.

The JVM startup tests use synthetic data. First-frame timing, process liveness,
secure-runtime readiness and actual UI/accessibility are separate evidence. A
loading screen appearing promptly is not proof that conversations or radio
are ready. Physical service deadlines, revocation and background behavior still
require Mesh Lab testing.

## Device and accessibility validation

An isolated emulator can establish installation, process startup and absence of
an immediate observed crash. It cannot establish BLE interoperability, range,
relay behavior, background delivery or battery behavior. A missing GUI or audio
surface leaves GUI/TalkBack unqualified.

Real-device Mesh Lab work requires available operator-approved hardware and
synthetic peers. Include public broadcasts and private QR contacts, invalid or
expired QR input, exact session identity and key rotation, blocked contacts,
revocation, retries, fragmentation, airplane mode with Bluetooth enabled,
background / force-stop / battery policies, and mixed iOS/Android peers. Public
messages have no recipient receipt ticks; a private local handoff is not proof
of remote receipt or reading. No real contact or location data is needed.

This fork validates the native Wesh experience. Production architecture and
radio adapters are separate roadmap decisions. MeshCore and Meshtastic support
are outside V1. A local test candidate does not authorize store upload,
tester invitation, production publication or a regulatory declaration.
