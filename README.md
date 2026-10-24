# Wesh Android validation fork

This repository validates Wesh’s native Android experience on the upstream
[BitChat Android](https://github.com/permissionlesstech/bitchat-android) codebase.
It is an experimental fork, with production architecture still subject to the
cross-platform physical-device validation sprint.

Wesh V1 combines a public local Bluetooth mesh conversation with private
contacts verified by scanning each other’s live QR code. Public messages can be
read, relayed and retained by mesh participants. Private identity verification
is local to this phone; a successful local handoff does not prove remote receipt
or reading. Geographic and Wi-Fi Aware routes remain disabled. Internet is off
by default and becomes available only after the user accepts the disclosure and
turns it on; supported Internet traffic must then use the embedded Tor client.
These product gates do not turn packaged upstream dependencies into absent code.

The app uses the system typeface, a polished silver / pure black interface and
an isolated application ID, `org.samouraiworld.wesh.android`. It does not replace
an upstream BitChat installation. MeshCore and Meshtastic adapters are long-term
roadmap exploration outside V1.

## Current Beta 3 candidate

The checked-in phone app declares **1.0.0 (103)**. `:app` is the repository's
only Gradle product module; there is no separate Wear build or Wear release in
this candidate.

- The complete phone JVM collection passes: **1,380 tests in 180 suites**, with
  no failure, error or skip.
- Strict phone lint passes with `abortOnError = true` and
  `checkReleaseBuilds = true`. Its current report contains **0 errors, 0 fatals,
  268 warnings and 9 informational notes (`Hint`)**. The checked baseline contains
  **12 historical warnings**. Neither the report nor the baseline contains a
  `MissingTranslation` or `MissingQuantity` issue.
- English and French are the maintained product locales. Their **531
  translatable resource keys** have exact name parity, and the application
  locale configuration exposes only `en` and `fr`.
- The four vendored Arti libraries implement authenticated local SOCKS and
  destination-based circuit isolation. Within one Arti session, reconnects to
  the same normalized DNS host reuse the same isolation token and may reuse
  circuits, while distinct hosts receive different isolation tokens and cannot
  share circuits. This is source, native and automated-test evidence, not a
  claim that Tor or relay behavior has been qualified on a physical phone.

Physical Android/iOS Mesh Lab, background and battery behavior, TalkBack,
real-device 16 KiB page-size behavior, upgrade through the intended distribution
channel and Play-installed Beta 3 are **NOT RUN** for this candidate. They remain
release gates; automated tests and reproducible artifacts do not substitute for
them. Store publication, public rollout and regulatory classification therefore
remain unqualified. Raw APKs, artifact hashes, command logs and detailed
provenance stay local.

## Build and test

Use JDK 21, the SDK / Build Tools pinned in `gradle/libs.versions.toml` and the
checked-in Gradle wrapper. See [CONTRIBUTING.md](CONTRIBUTING.md) for the evidence
and privacy rules.

```sh
python3 tools/arti-build/test-provenance.py
tools/arti-build/verify-checksums.sh
./gradlew :app:assembleDebug testDebugUnitTest
./gradlew lintDebug
```

The four Arti libraries are tracked vendored build inputs. The phone’s
`preBuild` gate checks both their hashes and the exact wrapper, lock and pinned
build-source hashes. A source-only dependency edit with stale libraries is
rejected. Follow the [native rebuild guide](tools/arti-build/README.md), including
two clean native builds, comparison of the four packaged libraries and all
three native-symbol artifacts, and binding the archive to the canonical AAB.

The native lock patches rustls to 0.23.46 and rustls-webpki to 0.103.15. It still
contains `rsa 0.9.10` affected by RUSTSEC-2023-0071 and unmaintained bincode /
paste warnings. Beta 103 carries a conditional, client-only risk acceptance
after review found no reachable private-RSA operation in the six exported JNI
paths. This is not a fix, an advisory ignore, removal of linked RSA code or an
advisory-free native graph. Any JNI, Arti-feature, relay/onion-service,
key-manager or dependency-graph change reopens the stop-ship review.

## Identity parser and interoperability evidence

The QR verifier rejects malformed binary verification controls with unsigned,
bounded lengths, exact field sizes and no trailing fields. Ten parser
regressions include the formerly throwing high-bit length inputs. Existing
challenge/response frames and the signing preimage are unchanged: 85 / 151
bytes including the message type, with a 103-byte response signing preimage
excluding that type.

Four shared signed-QR tests use the same immutable synthetic fixture as iOS,
the real signature verifier, tampered fields and clock boundaries at ±300 /
301 seconds. These are API and source-level wire checks; live scanning, mixed
client sessions and physical transport interoperability remain open. Test
fixture URL, filename and test-class markers were absent from the APK. Pattern
collisions in native lookup tables are not evidence of semantic key leakage
or a proof that every matching byte pattern is absent.

## Validation and contribution

The [original baseline](docs/validation/BASELINE.md) describes the upstream
starting point, including its old package/version and toolchain results; those
facts are historical. Current candidate facts belong above and in the local
provenance record. Source-only unit tests and build checks do not substitute for
physical Mesh Lab evidence.

Use feature branches and pull requests. Keep raw APKs, build logs, signing
material, contact data and device identifiers local. Synthetic fixtures are
required. Read [AGENTS.md](AGENTS.md) before working on the fork.

## Upstream references and license

The fork retains the existing upstream mesh / Noise wire formats while adding the
Wesh UI and local identity gates. Unchanged wire formats still need physical
cross-client validation. Upstream Internet, location, store and release
procedures describe upstream functionality, not enabled Wesh routes or an
authorization to publish this candidate.

- [Upstream Android repository](https://github.com/permissionlesstech/bitchat-android)
- [Upstream iOS repository](https://github.com/permissionlesstech/bitchat)
- [Wesh Android release guide](docs/maintainer-release-guide.md)
- [Wesh reproducible-build guide](docs/reproducible-builds.md)

The checked-in [LICENSE.md](LICENSE.md) is GPLv3. The upstream public-domain
claim conflicts with that file, as recorded in the historical baseline.
Preserve third-party notices and licensing information; distribution requires
license and corresponding-source review. Do not infer distribution terms from
old upstream marketing text.
