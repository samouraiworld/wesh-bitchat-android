# Android 107 internal test candidate

This preparation keeps version name `1.0.0` and advances the Play version code
to `107`. Its purpose is to test the native keystore hardening reviewed in
[PR #141](https://github.com/samouraiworld/wesh-bitchat-android/pull/141).
Unlike the distributed 106 binary, that native client explicitly disables its
Tor keystore and refuses implicit or enabled configuration before bootstrap.
It does not remove the linked generic RSA/private-key implementation.

The source preparation changes the version code, its exact test assertion and
the self-ANNOUNCE test fixture's synchronization. That fixture now awaits the
finished direct-link callback and the actual security rejection instead of
polling a pre-callback counter or waiting a fixed delay. All link, presence and
relay assertions remain intact; no transport implementation changes.
The reviewed native libraries and manifests, toolchain pins, dependency lock,
protocol and trust rules from the merged hardening change are preserved. No
symbol archive, release tag or signed artifact is added.

## Native provenance

PR #141 merged as `014400fa117c903acdbdd106159679e1f0501e29`. The complete
native import was reviewed at `0f959df23b2493120825abf141c0c8117df1694a`.
Independent clean four-ABI builds and canonical symbol comparisons passed in
[the source probe](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40188399015)
and [the final import probe](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40190882233).
The unchanged native checksum gate, JVM tests, lint, debug build and two
reproducible unsigned releases passed in
[the import CI](https://github.com/samouraiworld/wesh-bitchat-android/actions/runs/40190882240).
The source manifest and four packaged library hashes remain authoritative;
they must not be rewritten without a complete reviewed native rebuild.

## Release and test boundary

This PR prepares source only. Creating a release tag, signing an AAB, uploading
native symbols and publishing to the internal Play track require separate
approval of the frozen candidate. Use the verified unsigned artifact of that
immutable candidate, sign that exact AAB with the registered upload key and
bind the matching four-ABI symbol archive to the final package. This note does
not assert that 107 is already available from Play.

Fresh installation and upgrade from 106 must be tested on the exact new Store
binary. Cover Internet OFF and opt-in bootstrap, authenticated proxy traffic,
stop/restart, sleep/wake, emergency erase and recovery, plus existing nearby
public/private messaging and QR trust flows. Keystore tests in CI use inert
clients and synthetic files; they do not replace those device scenarios.
Results for Android 105 or 106, including any historical iOS/Android test pair,
do not qualify 107. Android 107 is also distinct from an iOS build numbered 107.

`RUSTSEC-2023-0071` remains open. The compiled runtime policy is a bounded
surface reduction, not an advisory fix. A candidate-specific internal risk
record must bind the final source, package and symbol identities. No public
risk acceptance, public release approval, physical qualification or ANSSI
approval is conferred here. Public Android remains NO-GO pending its separate
risk and release decision.
