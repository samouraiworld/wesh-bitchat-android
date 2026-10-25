# Rebuilding Arti for Android

The four committed `libarti_android.so` files are built from the official Arti
source plus the JNI wrapper in this directory. The supported build path is the
pinned Linux container:

```bash
ARTI_SYMBOL_OUTPUT_DIR=.reproducible-build/native-symbols-first \
  tools/arti-build/rebuild-in-container.sh
tools/arti-build/verify-checksums.sh
```

The container builds all four Android ABIs, verifies the exported JNI symbols
and rejects native binaries containing host-specific paths. It then rewrites
`tools/arti-build/SHA256SUMS` and `tools/arti-build/SOURCE_SHA256SUMS`. It also
writes `wesh-android-native-debug-symbols.zip`,
`NATIVE-SYMBOLS-BUILDINFO.json`, and `NATIVE-SYMBOLS-SHA256SUMS` to the empty
directory selected by `ARTI_SYMBOL_OUTPUT_DIR`.
These manifests bind the four vendored libraries to the reviewed lock, wrapper,
and pinned build inputs. The phone `preBuild` task rejects missing manifests or
changed input hashes; update them only through a successful native rebuild.
The builder captures read-only copies of the wrapper, dependency lock, and
pins before cloning or compiling; those copies supply the compiled wrapper.
It compares the active inputs with that snapshot before publishing its hashes.
An edit followed by a restore cannot change the captured wrapper being built.
This guards ordinary concurrent edits; it is not a claim of protection against
a hostile process that can alter the build environment. Nonempty `patches/`
directories are rejected until their inputs have explicit provenance support.
The container mounts and executes these repository scripts; its image copies
only `TOOLCHAIN.env`, and runtime checks verify the pinned tool versions.
Checksums identify inputs and outputs; the two clean rebuilds below establish
whether those outputs can be reproduced.

## Pinned inputs

- `TOOLCHAIN.env`: Arti tag and commit, stable build epoch, Rust, `cargo-ndk`,
  Android NDK, and immutable Debian snapshot
- `rust-toolchain.toml`: Rust release and Android targets
- `Cargo.lock`: complete Rust dependency graph
- `Dockerfile`: digest-pinned Rust base image and checksum-verified NDK archive
- `Cargo.toml` and `src/lib.rs`: JNI wrapper package and source
- `build-arti.sh` and `provenance.sh`: build, snapshot, privacy, alignment, and
  checksum gates
- `rebuild-in-container.sh`: pinned image, platform, mounts, build environment,
  epoch, and release-output boundary
- `native_symbols.py`: deterministic archive creation and stripped/unstripped
  SHA-256, GNU build-id, DWARF, symbol, inventory, and AAB binding gates

Rust source paths are remapped to stable virtual paths, incremental compilation
is disabled, and `SOURCE_DATE_EPOCH` is pinned to the value used for the
committed libraries.

## Updating Arti or the native toolchain

1. Update the tag, full source commit, stable build epoch, tool versions,
   archive name, and archive checksum in `TOOLCHAIN.env`.
2. Update `Cargo.toml` if Arti changed its crate features or wrapper version.
3. Regenerate `Cargo.lock` against the exact Arti checkout and review every
   dependency change:

   ```bash
   tools/arti-build/rebuild-in-container.sh --update-lockfile
   ```

4. Update the digest-pinned base image and `rust-toolchain.toml` when changing
   Rust.
5. Run two clean rebuilds into distinct empty symbol directories, compare all
   three artifacts byte-for-byte, and verify the final committed checksums:

   ```bash
   ARTI_SYMBOL_OUTPUT_DIR=.reproducible-build/native-symbols-first \
     tools/arti-build/rebuild-in-container.sh
   ARTI_SYMBOL_OUTPUT_DIR=.reproducible-build/native-symbols-second \
     tools/arti-build/rebuild-in-container.sh
   tools/arti-build/compare-symbols.sh \
     .reproducible-build/native-symbols-first \
     .reproducible-build/native-symbols-second
   tools/arti-build/verify-checksums.sh
   ```

6. From the same committed source, build the canonical unsigned app and bind
   the symbol archive to its AAB:

   ```bash
   tools/reproducible-builds/build-in-container.sh \
     .reproducible-build/native-app-check
   python3 tools/arti-build/native_symbols.py verify-release \
     --archive .reproducible-build/native-symbols-first/wesh-android-native-debug-symbols.zip \
     --buildinfo .reproducible-build/native-symbols-first/NATIVE-SYMBOLS-BUILDINFO.json \
     --aab .reproducible-build/native-app-check/bitchat-android-release-unsigned.aab
   ```

7. Run the Android tests and the two-build release comparison described in
   [`docs/reproducible-builds.md`](../../docs/reproducible-builds.md).

Do not build release native libraries with an unpinned local NDK or Rust
toolchain. `build-arti.sh` deliberately rejects mismatched tool versions.

If a repeated macOS Docker build stops before compilation with Git's
`dubious ownership` check on `tools/arti-build/.arti-source`, first confirm
that exact directory is ignored with `git check-ignore`, remove only that
generated clone, and rerun into the still-empty output directory. Do not add a
global `safe.directory` exception and do not continue from a partially filled
release directory.

## Dependency security and local validation

The reviewed Android lock patches `rustls` to `0.23.46` and `rustls-webpki`
to `0.103.15`, while retaining the Arti tag, source commit, JNI API, and all
other dependency versions. A source-lock audit does not prove which code paths
an APK reaches or that a previously built library includes the patch.

The locked graph still includes `rsa 0.9.10`, affected by
[RUSTSEC-2023-0071](https://rustsec.org/advisories/RUSTSEC-2023-0071.html),
which has no published compatible fix. Generic RSA private-key parsing code is
linked through Arti and key manager dependencies. The unified `tor-keymgr`
feature remains present. The wrapper explicitly disables its runtime keystore
as described below; this does not remove the linked key manager or RSA code.
`bincode 2.0.1` and `paste 1.0.15` also carry unmaintained warnings. Do not
describe this native graph as free of advisories. Wesh disabling its Internet
routes does not remove the packaged native dependencies. Public Android
release remains NO-GO for the unresolved RSA advisory.

### Runtime keystore policy

The wrapper enables `arti-client/keymgr` to expose upstream's public storage
configuration setter, then sets
`storage().keystore().enabled(BoolOrAuto::Explicit(false))`
for every client. After building the configuration, it refuses both an
implicit default and an enabled store before calling `create_bootstrapped`.
There is no provisioned Tor private key in this client integration.

This feature also activates `tor-hsclient/keymgr` and its
`__is_experimental` marker. The resolved package versions and dependency lock
are unchanged. `tor-keymgr/keymgr` was already enabled transitively by
`tor-chanmgr`. This change does not enable `onion-service-service`,
`onion-service-cli-extra`, or `arti-client/experimental-api`, and adds no JNI
entry point. It is a bounded reduction of runtime storage access, not a fix
for `RUSTSEC-2023-0071` or removal of private-key code.

Feature review against the pinned Arti source commit:

| Source | Effect and boundary |
| --- | --- |
| `crates/arti-client/src/config.rs`, `StorageConfig` | `keymgr` compiles the keystore field and public builder setter; the internal accessor now uses that field instead of constructing a default. The wrapper sets an explicit false and checks the effective configuration. |
| `crates/tor-hsclient/Cargo.toml`, `keymgr` | Activates `tor-keymgr/keymgr` and the empty `__is_experimental` marker. No `cfg(feature = "keymgr")` or `__is_experimental` block exists in this crate's Rust source at the pinned commit. Its existing `src/keys.rs` defines supplied key material and key specifiers; the added feature introduces no separate file read/write block there. |
| `crates/arti-client/src/client.rs`, `InertTorClient::create_keymgr` | `primary_kind() == None` returns `Ok(None)` before calculating or opening `state/keystore`, constructing a `KeyMgr`, or iterating secondary stores. The bootstrap constructs this same inert client. |
| `crates/arti-client/src/client.rs`, `connect_with_prefs` | The existing onion-client path may read an `HsClientDescEncKeypair` only inside `if let Some(keymgr)`. The disabled configuration leaves this option empty. No key is fetched or generated by that path. |
| `crates/arti-client/src/client.rs`, service-discovery key operations | Generate, rotate, insert, and remove require onion-client/keymgr and the absent `experimental-api`. Retrieve also requires `experimental-api`. Direct key-manager getters require the absent `onion-service-cli-extra`; onion-service creation requires the absent `onion-service-service`. |

This review does not claim an identical compiled surface just because the
lock is unchanged. The new configuration field and feature set are deliberate;
generic private-key parsing remains linked through the already-enabled key
manager dependencies. The boundary is the checked client configuration and
the absence of the APIs above from the wrapper and JNI surface. The wrapper
also exposes no reconfiguration or key-manager handle through JNI.

The `keystore_policy_tests` in `src/lib.rs` exercise the real upstream public
`TorClient::builder().config(config).create_inert()` API, which never connects
to the network. They check that no keystore is created and that an unusable
synthetic file at `state/keystore` is left intact. Negative tests reject the
implicit default and an explicitly enabled store. Run these tests in the
pinned Linux builder against the exact Arti checkout and reviewed wrapper,
from the Arti checkout root where the builder stages `arti-android-wrapper`:

```bash
cargo test --locked --offline --manifest-path arti-android-wrapper/Cargo.toml \
  --lib keystore_policy_tests
```

Fetch the locked dependencies before disabling the test container's network.
The test-only Android logging symbol shim permits host linking and is absent
from all packaged Android libraries. These inert-client tests do not replace
the two clean four-ABI rebuilds, symbol comparison, AAB binding, or Store
qualification of Internet startup, stop, and emergency erase.

### Historical Beta 103 conditional RSA risk acceptance

RUSTSEC-2023-0071 remains open; the Beta 103 decision is a conditional risk
acceptance, not a fix or an advisory ignore. Static review of this exact
client-only candidate found only the six documented JNI exports. Arti creates
client channels with `keymgr=None`, rustls uses no client authentication, and
no wrapper, bootstrap, or connect path was found that reaches RSA private-key
generation, parsing, signing, or decryption. The observed Tor client paths use
RSA public-key verification only. This finding is about absence of a reachable
private-key operation sink in the reviewed runtime surface, not absence of
linked private-key code or a keystore.

The acceptance applies only to Beta 103 while the build remains client-only,
with no relay, onion-service service, X.509 signing, JNI key-manager access, or
provisioned RSA private keys. Any change to that surface, the JNI exports, the
wrapper, Arti features, or the dependency graph reopens a stop-ship review.
Before final packaging, repeat the hash, export, linked-code, and dependency
checks on all four Android libraries and record the result in release evidence.
The runtime policy above explicitly disables the keystore in subsequent source.
It does not extend that historical risk acceptance to a new candidate. Removing
unused key-manager and RSA-private functionality, or incorporating a corrected
upstream graph, remains separate work requiring reviewed source and clean
four-ABI rebuilds.

Audit the exact lock before rebuilding, using a current advisory database:

```bash
cargo audit --file tools/arti-build/Cargo.lock
python3 tools/arti-build/test-provenance.py
```

The provenance regression checks use synthetic temporary inputs and verify
that changing the lock, wrapper, or library, removing or truncating a manifest,
adding an unsupported patch, failing the strings scan, or embedding a host path
causes verification to fail. Large early matches and single-backslash Windows
paths are included. Snapshot tests also cover concurrent source edits and an
edit followed by a restore. Raw audit/build logs and
APK candidates stay local.

## JNI surface

The wrapper exports the native methods used by `ArtiNative`:

- `getVersion`
- `setLogCallback`
- `generation`: the stop generation, read when a start begins
- `initialize(dataDir, generation)`: a fresh Tokio runtime and a freshly
  bootstrapped client per call; returns `-4` when a `stop` came after
  `generation` was read or cancelled the bootstrap, and `-5` without starting
  when an earlier runtime did not stop in time
- `startSocksProxyWithAuth`: SOCKS5 on `127.0.0.1:<port>`, RFC 1929
  username/password only, with per-start credentials; 3 s for the greeting,
  authentication and request, at most 16 connections in that handshake and 64
  authenticated connections at a time; CONNECT accepts only validated DNS
  names, reads every request field across arbitrary TCP fragmentation, and
  rejects IPv4/IPv6 targets and textual IP literals; logs never name a
  destination. Each normalized destination host has a stable
  `IsolationToken` used through `connect_with_prefs`, distinct hosts cannot
  share a circuit, and stopping Arti discards the complete token registry
- `stop`: cancels a bootstrap in progress, drops the client and shuts its
  runtime down; when it returns `0` no Tor task remains and the data directory
  can be deleted. It returns `-5` when a runtime did not stop within 3 s, now
  or earlier in the process

The start/stop bookkeeping, the teardown deadline and the SOCKS credential and
host name checks are std-only modules of `src/lib.rs` with their own unit tests.

The build fails if any expected symbol is absent. The pinned NDK `readelf`
check requires every LOAD segment in each 64-bit library to have at least
16 KiB alignment. No 16 KiB compatibility claim is made for the 32-bit ABIs.

## References

- [Arti source](https://gitlab.torproject.org/tpo/core/arti)
- [Cargo lockfiles](https://doc.rust-lang.org/cargo/faq.html#why-have-cargolock-in-version-control)
- [Rust source-path remapping](https://doc.rust-lang.org/rustc/remap-source-paths.html)
- [Install a specific Android NDK](https://developer.android.com/studio/projects/install-ndk)
