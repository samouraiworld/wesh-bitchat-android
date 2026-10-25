# Android store assets and candidate 107 capture review

The existing `play/*/phoneScreenshots` PNGs are historical candidate 104 output, not approved
candidate 107 captures. Their renderer hid OS bars and bypassed the production Activity shell;
the connection scene used sheet content as a full screen. Do not upload that legacy set as 107.
Canonical icon/feature artwork below is unaffected by this capture-only change.

This directory contains only reproducible Wesh artwork and synthetic store fixtures. It contains no
physical-device screenshot, account, contact, message, identifier, network detail, or location.

## Canonical artwork

`source/WeshAppIcon.svg` is the byte-for-byte local copy of the public iOS Wesh artwork, SHA-256
`bbebcedf6e040de562a0a616bd9864136c6d10e289feeba2942905b84becec91`. The generator validates that
digest before rendering anything. It produces:

- `play/icon-512.png`: opaque 32-bit 512 x 512 Google Play icon;
- `play/fr-FR/feature-graphic.png`: opaque 32-bit 1024 x 500 French feature graphic;
- `play/en-US/feature-graphic.png`: opaque 32-bit 1024 x 500 English feature graphic;
- every legacy launcher PNG and adaptive compatibility PNG under `app/src/main/res/mipmap-*`.

The adaptive XML launchers use the same W geometry. The prior BIT vectors are not retained.

The PNG rasterizer and encoder are intentionally not run with the host Python. Both local and CI
use the same `linux/amd64` container, whose Python base is digest-pinned and whose Pillow version is
pinned in `requirements.txt`. The bundled Archivo and Geist files are the only fonts used.

```sh
tools/store-assets/generate_store_assets.sh
tools/store-assets/verify_store_assets.sh
```

`generate_store_assets.py` is an internal container entry point. Running it directly on macOS or
another host is unsupported because native rasterizer and zlib implementations can differ while
producing visually equivalent pixels or PNG streams.

## Synthetic screenshots

The `storeScreenshot` build type is debug-signed, uses the `.storecapture` application ID suffix,
and loads only `app/src/storeScreenshot/assets/store_scenes.json`. Its dedicated Application does
not initialize identity, mesh, Bluetooth, or Internet. Its manifest removes all data-bearing
device/network permissions, notification and foreground-service permissions, AndroidX's generated
receiver permission, and every product runtime component. Release still applies `FLAG_SECURE`;
only the source-set-exclusive capture Activity can clear it. The merged-manifest gate fails if any
permission or product runtime component reaches the capture APK.

The seven deterministic scenes are, in upload order:

1. home and nearby devices;
2. public local room;
3. verified private chat;
4. QR exchange completion;
5. settings and erase safety;
6. Bluetooth/Internet connection state;
7. about and privacy access.

The current capture uses the same production screen composables and the real `ConnexionSheet`
over Home, with Internet and boot auto-start off in the fixtures. It mirrors standard
`WeshPaveTheme`/Material3 `Scaffold` infrastructure; it does **not** share an extracted product root.
Production `MainActivity -> OnboardingFlowScreen COMPLETE -> WeshScreen -> WeshPaveApp` computes
Scaffold padding but does not forward that modifier in COMPLETE. The fixture preserves this
behavior, with real screens consuming their own system-bar insets, rather than applying padding
twice. It keeps visible OS bars and standard edge-to-edge styling without per-scene contrast
overrides. The source manifest hashes the root-chain files and binds the unchanged complete
`app/src/main` tree to production candidate commit `5008c95ae99933c180ee2ac6f767dd0e9c203a21`.

Capture is restricted to an explicitly named, dedicated 1080 x 1920 emulator; it never inventories
connected devices or reads physical-device data. The APK has no runtime permissions. Synthetic
status-bar demo values and disabled animation scales are confined to that emulator. Capture waits
for composition-frame readiness, then requires three consecutive identical pixel samples within
fifteen seconds. An unsettled scene fails closed; only a temporary stable frame is sanitized.
The script verifies the synthetic API35/1080x1920/density420 emulator context without recording
serial, hostname or fingerprints, sanitizes PNG metadata, verifies opacity, and writes to a **new**
output directory. It never replaces historical evidence or builds the APK implicitly.

Build the isolated APK and validate the manifest produced by Android's merger before capture:

```sh
./gradlew assembleStoreScreenshot
python3 tools/store-assets/verify_store_manifest.py
```

```sh
ANDROID_STORE_CAPTURE_SERIAL=emulator-5554 \
ANDROID_STORE_CAPTURE_OUTPUT=.validation/candidate107-unbound \
  bash tools/store-assets/capture_android_store_screenshots.sh
```

The dedicated `store-capture.yml` PR/workflow-dispatch job builds only on disposable CI, verifies
the merged capture manifest and Release APK exclusion, then captures seven scenes per language.
It retains only the fourteen sanitized synthetic PNGs and `manifest.json`, not raw logs, APKs,
device identifiers or local-environment data. No emulator/image installation or app build is
required on the maintainer's computer.

The manifest records `capturedFromCommit`, source hashes and `captureCommit: null` with status
`UNBOUND_REVIEW_REQUIRED`. This is evidence for visual review, not self-approval. After independent
review, the Lead can explicitly bind `captureCommit` to `capturedFromCommit` and mark `REVIEWED`;
`store_capture_manifest.py verify --require-reviewed` refuses unreviewed evidence. Generation
cannot approve its own output. No automatic binding to iOS 108, Android production publication,
transport behavior or physical-device qualification is implied.

If no suitable emulator/CI output is available, no new captures are claimed. Review and explicitly
bind the actual CI output before publishing screenshots; no Play Console upload occurs here.
