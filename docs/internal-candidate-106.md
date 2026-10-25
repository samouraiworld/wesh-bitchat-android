# Android 106 internal test candidate

This candidate keeps version name `1.0.0` and advances the Play version code
to `106`. It includes the reviewed field-usability changes from PR #139:

- detect missing camera hardware before permissions/settings and retain the
  personal QR; prefer the rear camera and support front-only devices;
- explain Internet failures in English and French with safe recovery steps,
  pending-message status, and the nearby Bluetooth alternative;
- show the QR name and the exact contact fingerprint above local notes;
- describe the explicit Open the chat action after mutual verification.

The native libraries, JNI surface, dependency graph, transport, trust rules,
and storage are unchanged from the previous internal candidate. The separate
native-keystore hardening work is not included in this release.

## Release boundary

Use only the verified unsigned artifact from the immutable
`v1.0.0-beta.3-android.106` tag, then sign that exact AAB locally with the
registered Play upload key. Ship only to the existing internal test track,
with the matching four-ABI native debug symbols.

The physical-device results for version 105 are historical evidence for that
exact binary. They do not qualify 106. The new Store binary must be installed
and tested, particularly missing-camera/front-camera handling, QR exchange,
connection recovery guidance, local contact identity, and existing messaging.

`RUSTSEC-2023-0071` remains open in the packaged native graph. A fresh,
candidate-specific internal risk record must bind the reviewed source and
final artifact hashes. This document neither accepts public risk nor permits
a public GitHub or Play release. Public Android remains NO-GO.
