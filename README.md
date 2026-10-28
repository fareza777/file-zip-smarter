# Hatch

A premium, local-first archive extractor for Android, built around one idea:
**unpack a ZIP without keeping a second copy of it.**

Hatch pairs a Rust extraction core with Jetpack Compose and Material 3. The
default look is **Paper**, a warm light theme; Ink (dark) and system Auto are
one tap away in Studio.

<p>
  <img src="docs/screenshots/home.jpg" width="230" alt="Hatch home screen" />
  <img src="docs/screenshots/inspector.jpg" width="230" alt="Archive inspector with reclaim readiness" />
  <img src="docs/screenshots/reclaim-mode.jpg" width="230" alt="Reclaim selected as the default extraction mode" />
  <img src="docs/screenshots/queue-paused.jpg" width="230" alt="Paused reclaim job with resume" />
</p>

## What it does

- **In-place ZIP reclaim.** Extracts a ZIP directly into the granted folder and
  consumes the source as entries are committed. Peak extra space is roughly the
  largest selected file instead of the whole archive. The source is truncated
  or deleted only after every entry is written and verified.
- **Safe fallback.** If an archive is read-only, encrypted, split, or contains
  links/unsafe paths, Hatch falls back to smart extraction: fresh files, source
  untouched.
- **Formats.** ZIP, RAR / RAR5, 7z, TAR, TAR.GZ, GZIP. ZIP, RAR and 7z
  passwords supported.
- **Multipart.** Split ZIP (`.z01` + `.zip`), 7z (`.7z.001`) and RAR
  (`.partN.rar`, `.rNN`) sets are resolved through the SAF tree, staged in
  private cache, and opened with the bundled engines.
- **Pause, resume, recovery.** Jobs run in a foreground service, survive
  rotation and process death, and resume from a journal after a crash.
- **Batch extraction.** Select several archives in Files and hatch them in one
  queue.
- **File manager.** Browse the landing tree, search, sort, grid/list, rename,
  delete, share, and open files without a second file-manager app.
- **Storage Lens.** See packed vs unpacked size, entry mix, compression ratio
  and reclaim eligibility before extracting.
- **Password vault (optional).** Archive passwords encrypted with an Android
  Keystore AES-GCM key. Nothing leaves the device.
- **Premium UI.** Material 3, large type, generous spacing, hand-built type
  scale, no nested-card clutter, no dead screens.

Hatch requests no storage permission: all file access goes through the Storage
Access Framework with a folder the user explicitly grants. It also has no
`INTERNET` permission, no analytics, no accounts and no ads.

## Architecture

```
Compose UI
  └─ HatchViewModel ── JobManager (foreground data-sync service, JSON journal)
        └─ NativeEngine (JNI)
              └─ Rust core (hatch_core)
                    ├─ open.rs        format sniffing and routing
                    ├─ zip_engine.rs  ZIP + AES/ZipCrypto, reclaim planner
                    ├─ rar_engine.rs  bundled UnRAR, volumes, passwords
                    ├─ sevenz_engine.rs
                    ├─ tar_engine.rs / gzip_engine.rs
                    └─ reclaim.rs     in-place truncation + journal
                          └─ Sink: SafExtractionSink → user-granted folder
```

The native layer only reads archive data. Writes go through a `Sink`; on
Android that sink is backed by `DocumentsContract`, so extracted files land in
the user's folder rather than in app storage. Multipart sets are the one
exception: they are copied to private cache because the engines need real
sibling paths for volume discovery.

## Build

Requirements:

- JDK 17
- Android SDK 36 (`compileSdk 36`, `targetSdk 36`, `minSdk 26`)
- Android NDK `27.2.12479018`
- Rust 1.85+ with `aarch64-linux-android` and `x86_64-linux-android` targets

```bash
rustup target add aarch64-linux-android x86_64-linux-android

export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./scripts/build-core.sh all      # builds jniLibs for arm64-v8a + x86_64
./gradlew assembleRelease        # signed APK (if keystore.properties exists)
./gradlew bundleRelease          # signed AAB for Google Play
```

`scripts/build-core.sh` refuses to package a library that embeds static bionic
and verifies that every artifact links the shared `libc.so` stub. Do not bypass
those guards; a static-libc build crashes inside scudo at runtime.

### Signing

Create `keystore.properties` in the repository root (never commit it):

```properties
storeFile=hatch-release.jks
storePassword=...
keyAlias=hatch
keyPassword=...
```

Without this file the release build is unsigned. The app is also configured for
R8 minification and resource shrinking.

## QA evidence

Validated on a physical **Redmi Note 11 (2201117TY), Android 13, 1080×2400**,
plus a Pixel-class emulator, with release builds:

| Check | Result |
| --- | --- |
| 126 MB ZIP, 3 stored entries, in-place reclaim | Source deleted, 3 outputs, byte-identical |
| 503 MB ZIP, 3 stored entries, in-place reclaim | 3 outputs byte-identical (MD5 checked) |
| Pause at 111 MB of 503 MB, resume | Completed, byte-identical |
| Force-stop mid-reclaim, relaunch, resume | Recovered from journal, byte-identical |
| 3-volume RAR (`split.part1..3.rar`) | 1.2 MB entry, MD5 identical to source |
| Password RAR | Unlocked and extracted |
| ZIP/TAR inspector, selection, Storage Lens | Verified |
| Paper/Ink/Auto themes and all four tabs | Verified at 1080×2400 |
| Rust core tests | 9 passed, 0 failed |

Post-update and cold-start runs were also checked: the SAF grant survives an
APK update and the reclaim write path keeps working.

## Google Play checklist

- Build a signed **AAB** with `./gradlew bundleRelease` (Play does not accept
  an APK for new apps).
- `versionCode 1`, `versionName 1.0.0`; bump both for every upload.
- 64-bit ARM is included for devices; x86_64 is for emulators.
- Complete **Data safety** as “no data collected or shared”. There is no
  network permission.
- Provide a privacy policy URL (recommended even with no data collection).
- Complete content rating, target audience and ads declarations (no ads, no
  in-app purchases).
- Include the UnRAR notice in the listing or app; it is already exposed in
  **Studio → Open-source licenses**.
- Test the AAB with bundletool or an internal-testing track before production.

## Licensing audit

- **UnRAR** — `core/vendor/unrar_sys/vendor/unrar/license.txt`. UnRAR is
  freeware, **not OSI open source**. It may be used to extract RAR archives, but
  its license forbids using the code to build a RAR compressor. Hatch is
  extraction-only and never creates RAR files. The notice ships with the app.
- **Fonts** — Space Grotesk, Space Mono and Instrument Serif are SIL Open Font
  License 1.1; license texts are in `app/src/main/assets/licenses/`.
- **Rust crates** — `zip`, `unrar`, `unrar_sys`, `sevenz-rust2`, `jni`,
  `crc32fast` and the rest keep their upstream MIT/Apache-2.0 licenses; see
  `core/Cargo.lock` and the in-app licenses screen.
- **Hatch application code** — no license has been chosen for the original
  code in this repository. Add one before accepting outside contributions.

## Known limitations

- In-place reclaim is **ZIP only** and is refused for encrypted ZIPs,
  symlinks, unsafe paths, read-only documents and partial selections.
- RAR, 7z and multipart inputs are always staged or extracted through the
  normal path; only ZIP gets zero-copy reclaim.
- Bzip2, XZ and Zstandard archives are reported as unsupported rather than
  guessed at.
- Splitting a ZIP/RAR is not implemented; Hatch extracts existing sets only.
