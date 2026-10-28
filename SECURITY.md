# Security policy

Wesh is an emergency messenger built on a fork of [BitChat Android](https://github.com/permissionlesstech/bitchat-android), maintained by Samouraï Coop. Reports about its security are taken seriously. This page says how to report, what counts as a vulnerability here, and what to expect.

## Reporting a vulnerability

**Email [security@samourai.coop](mailto:security@samourai.coop).** Do not report security issues through the upstream BitChat project: it does not ship Wesh and cannot fix it.

Please do not open a public issue for anything that could put people at risk before a fix ships. Wesh is meant for people in emergencies and hostile network environments; a public proof-of-concept can be acted on faster than a patch can reach them.

A useful report says what an attacker can do, against which build (Google Play version or commit hash), and how to reproduce it. A failing test or a packet capture is worth more than speculation about impact.

## What to expect

Wesh is maintained by a small team. The aim is to acknowledge reports within a week and to move on confirmed vulnerabilities immediately. You'll be kept in the loop by email, and credited in the fix unless you'd rather not be. There is no bug bounty.

If the issue also affects upstream BitChat, we will coordinate disclosure with its maintainers, and tell you before we do.

## Supported versions

Fixes ship to the latest Google Play release and `main`. Older releases are not patched; the fix is to update.

## Scope

In scope — the properties the app promises:

- Confidentiality and integrity of private messages and media (Noise sessions over BLE; Nostr direct messages to verified contacts in Internet mode)
- Identity: key handling, verification, impersonation, session binding
- The panic wipe actually destroying what it claims to destroy
- Metadata exposure beyond what the documentation already discloses (see `PRIVACY_POLICY.md`)
- Downgrade paths: anything that silently moves traffic from an encrypted path to a plaintext one
- Tor routing: anything that makes traffic bypass Tor while Internet mode is on, or opens a network connection while it is off
- Supply-chain integrity of the source and its vendored binaries

Out of scope — documented design properties, not vulnerabilities:

- Public visibility of mesh announces and geohash channels: broadcast content, nicknames, and public keys are public by design
- Bluetooth proximity being observable: anyone in radio range can tell a BLE device is present
- Mesh flooding/relay behavior inherent to a broadcast mesh (rate limits exist; the topology is what it is)
- Behavior of third-party Nostr relays
- Denial of service requiring physical proximity, and battery-drain attacks in general

If you're unsure whether something is in scope, report it privately anyway — a false alarm costs a few minutes; a real issue reported publicly can cost much more.
