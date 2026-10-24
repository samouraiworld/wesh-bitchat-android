# Android public-readiness 104

This candidate keeps version name `1.0.0` and advances the Play version code to
`104`. Its About screen links to the French or English privacy policy according
to the active app language.

## Native crash symbols: tooling addressed, Play verification pending

The pinned native build now retains a path-remapped, unstripped Arti library for
each of the four Android ABIs as a release-only symbol artifact. The app still
packages separately stripped libraries. The release gate binds both forms by
SHA-256 and GNU build-id, requires real DWARF and identifiable Arti/Rust symbols,
rejects host paths, and rejects symbol data inside the AAB.

The Release workflow builds the app and native symbols twice independently,
requires byte-identical replicas, verifies the four-ABI symbol archive against
the stripped AAB, and attests the combined verified output. The local
publication gate repeats that cross-check before any asset can be renamed for a
GitHub release.

This makes the Play native-symbol warning technically addressable. It does not
claim Play acceptance: `wesh-android-native-debug-symbols.zip` still has to be
uploaded for the exact version code, and Play must report symbols for all four
packaged ABIs before promotion.

## `RUSTSEC-2023-0071`: public-release blocker

The pinned native lock still contains `rsa 0.9.10`. It is pulled directly by
`tor-llcrypto` and `tor-key-forge`, and through `ssh-key` from `tor-keymgr`.
`arti-client 0.38.0` depends on both `tor-llcrypto` and `tor-keymgr`. The advisory
has no published compatible fix in this graph.

The reviewed runtime surface creates client channels with no key manager and
has no identified private-RSA operation reachable from JNI. That bounded
finding supported the documented Beta 103 conditional acceptance; it does not
remove the affected code and does not authorize a public release.

Removing `rsa` locally is not a safe application-layer update:
`tor-llcrypto` also uses RSA for Tor public-key verification, so dependency or
feature surgery can change interoperability and security semantics. A valid
resolution requires either an upstream-compatible fixed graph or a separately
reviewed Arti/crypto patch that preserves Tor public verification while making
private-key operations constant-time or absent. It then requires a new lock,
four-ABI rebuild, dependency audit, JNI reachability review, reproducibility
checks, and Tor interoperability validation.

Candidate 104 leaves the pinned dependency graph, RSA version, and reviewed JNI
surface unchanged. The packaged native hashes change because the deterministic
pipeline now retains debuginfo before creating separate `--strip-unneeded`
copies with explicit GNU build-ids; that does not resolve the RSA advisory.
Public promotion remains blocked on an explicit RSA resolution and review; the
Beta 103 waiver must not be reused.
