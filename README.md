<p align="center">
  <img src="docs/images/xanh-browser-logo.png" alt="Xanh Browser logo" width="128">
</p>

<h1 align="center">Xanh Browser — Android</h1>

<p align="center">
  A native, privacy-focused, multi-tab browser for Android 8.0 and newer.
</p>

<p align="center">
  <a href="https://github.com/LamPPKK/xanh-android/actions/workflows/android.yml"><img alt="Android build" src="https://github.com/LamPPKK/xanh-android/actions/workflows/android.yml/badge.svg"></a>
  <a href="https://github.com/LamPPKK/xanh-android/actions/workflows/codeql.yml"><img alt="CodeQL" src="https://github.com/LamPPKK/xanh-android/actions/workflows/codeql.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: LGPL-3.0" src="https://img.shields.io/badge/license-LGPL--3.0-blue.svg"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
</p>

Xanh Browser combines an Android-native interface with the device's serviced
Android System WebView. It provides persistent tabs and library data, isolated
private browsing where the provider supports it, encrypted portable backups,
content blocking, and optional Mozilla Accounts / Firefox Sync integration.

> **Release status:** version 1.0.0 is a release candidate, not a Play-ready
> release. Local build, unit-test and lint lanes pass. The hosted Android lane
> still has a pre-existing duplicate `llvm-readelf` packaging-verifier blocker;
> CodeQL and dependency-baseline lanes pass. Production also remains blocked on
> signed-artifact validation, the emulator/device matrix, live Sync
> interoperability, security review, and Google Play gates. See
> [RELEASING.md](RELEASING.md).

## Preview

<p align="center">
  <img src="docs/images/xanh-browser-android-browsing.png" alt="Xanh Browser displaying a web page" width="320">
  <img src="docs/images/xanh-browser-android-tabs.png" alt="Xanh Browser multi-tab overview" width="320">
</p>

<p align="center"><em>Direct captures from the current Android build using a clean emulator profile and neutral documentation content.</em></p>

## What is implemented

- Independent multi-tab browsing with process-death restoration
- Room-backed tabs, bookmarks, history, and download records with exported,
  versioned schemas
- Back/forward navigation, predictive back, sharing, desktop mode, file upload,
  and per-origin geolocation consent
- Phone, tablet, and foldable layouts built with Android Views and View Binding
- Downloads through Android DownloadManager and scoped storage
- Ad and tracker blocking for regular and private subresource requests
- Browser library for viewing and managing saved data
- Foreground-only, bounded WebView renderer recovery with exact dead-view cleanup
- Password-encrypted portable backup of regular tabs
- Optional bookmarks, history, remote tabs, and Xanh-only password sync through
  Mozilla Application Services
- Private browsing in a random AndroidX WebKit profile when the installed
  provider exposes `MULTI_PROFILE`

## Engine and Xanh WebView

All browser surfaces construct the Xanh-owned `XanhWebView` boundary. Its
current, explicitly reported backend is **Android System WebView**:

| Contract | Current value |
| --- | --- |
| Xanh WebView API | `0.1.0-alpha.1` |
| Backend ID | `android-system-webview` |
| Backend status | Serviced system fallback |
| Replacement target | `wpe-android` |

This repository does **not** ship an Android system WebView provider and does
not yet embed a custom Android browser engine. The full application will move
to the WPE backend only after it passes the API 26, multi-profile, downloads,
permissions, renderer recovery, accessibility, credential bridge,
content-blocking, 16 KiB, and device-matrix gates. The reviewed SDK revision is
recorded in [`XANH_WEBVIEW.lock`](XANH_WEBVIEW.lock).

The separately installable one-tab WPE experiment lives under
[`xanh-webkit/app-webkit`](https://github.com/LamPPKK/xanh-webkit/tree/main/app-webkit).
It has a separate application ID and does not replace this full Android app.

## Content blocking

Production packages call the `xanh-adblock-core` C ABI through JNA. The core is
built from the exact [`xanh-webkit`](https://github.com/LamPPKK/xanh-webkit)
revision in [`ADBLOCK_CORE.lock`](ADBLOCK_CORE.lock) and embeds Brave
[`adblock-rust`](https://github.com/brave/adblock-rust) 0.13.3. CI verifies the
source revision, native symbols, SHA-256 digests, three Android ABIs, and 16 KiB
ELF alignment.

Ordinary local builds without a verified native package use a bounded
seven-domain fallback. That fallback is deliberately small, and neither path
claims uBlock Origin feature parity: Android WebView does not expose every
extension interception primitive or all request metadata. Main-frame
navigation is never blocked by the subresource matcher.

## Sync and portable backup

The `sync-core` module pins Mozilla Application Services 155.0 for Accounts,
Places, Tabs, and Logins. It includes OAuth PKCE, encrypted account state,
WorkManager scheduling, exact Places identities, and a guarded native
credential bridge. Mozilla-hosted releases still require an approved client ID
and recorded interoperability evidence; self-hosted-only releases use a
separate explicit build mode. Details and remaining gates are in
[`docs/FIREFOX_SYNC.md`](docs/FIREFOX_SYNC.md).

Portable `.xanhbackup` files use PBKDF2-HMAC-SHA256 and AES-256-GCM. A snapshot
contains at most 50 regular HTTP(S) tab URLs, the selected tab, and the desktop
mode setting. It excludes cookies, passwords, form data, local storage, cache,
service workers, downloads, account tokens, and all private state. Files can be
saved through Android's Documents picker to an installed provider; Xanh never
receives that provider's cloud or Git credentials. See
[`docs/PORTABLE_BACKUP.md`](docs/PORTABLE_BACKUP.md).

## Privacy and security boundaries

- Safe Browsing is enabled when supported by the installed provider.
- Production cleartext traffic and mixed content are blocked.
- WebView navigation accepts only valid HTTP(S) URLs with a host.
- File access, geolocation, sharing, and external-app handoff require an
  explicit user action.
- Private mode fails closed when `MULTI_PROFILE` is unavailable; it never falls
  back to the regular cookie and storage profile.
- Private URLs are excluded from Room, Sync, credential filling, and portable
  backup. Explicitly confirmed downloads can remain on the device.
- Account state and the password vault use Android Keystore-backed protection;
  the vault locks on backgrounding or after five minutes.
- Release secrets are read only from private Gradle properties or environment
  variables and must not be committed.

JavaScript remains enabled for modern websites, so the WebView boundary, URL
validation, navigation policy, and permission handling are part of every
release security review.

## Requirements

- JDK 17
- Android SDK Platform 37.1 and Build Tools 36.1.0
- An API 26+ device or emulator for instrumentation tests
- For production content-blocker packaging: Rust 1.97.1, Android NDK
  29.0.14206865, and the `xanh-webkit` revision in `ADBLOCK_CORE.lock`

The checked-in wrapper downloads Gradle 9.7.1. Use it instead of a global
Gradle installation.

## Build and test

Run the standard local verification set:

```sh
./gradlew --no-daemon \
  :backup-core:testDebugUnitTest \
  :app:lintDebug \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleAndroidTest \
  :app:bundleRelease
```

Run instrumentation with an API 26+ device or emulator available:

```sh
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

| Output | Path |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Instrumentation APK | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| Verification AAB (unsigned unless upload-signing credentials are configured) | `app/build/outputs/bundle/release/app-release.aab` |
| Lint report | `app/build/reports/lint-results-debug.html` |

Without `XANH_ADBLOCK_NATIVE_DIR` (or `-PxanhAdblockNativeDir`), local builds
exercise the limited fallback blocker. A production build requires the verified
three-ABI native package, private upload signing, and one explicitly selected
Sync release mode:

```sh
./gradlew --no-daemon bundleProductionRelease
```

Do not upload an ordinary `bundleRelease` output to Google Play. Follow the
full signing, native-package, device, Sync, and review procedure in
[RELEASING.md](RELEASING.md).

## Verification and CI

- **Android:** builds the pinned native blocker, runs JVM tests and lint,
  compiles app and instrumentation artifacts, checks packaged legal resources,
  and emits a CycloneDX SBOM.
- **Android instrumentation:** exercises API 26, 30, 33, and 36 phone profiles,
  plus API 36 tablet and foldable profiles on its scheduled/manual matrix.
- **CodeQL and dependency review:** scan Java/Kotlin and reject high-severity
  dependency changes on pull requests.
- **Stable baselines:** scheduled verifiers compare AndroidX WebKit, direct UI
  dependencies, Mozilla Application Services, AGP, and Gradle pins with
  official release metadata and strict checksums.

CI bundles are verification artifacts, not signed Play artifacts. Version
1.0.0 remains blocked until the exact release commit also has recorded device
matrix, multi-provider WebView, Firefox interoperability, security-review,
signed-installation, and Play pre-launch evidence.

## Application identity

| Property | Value |
| --- | --- |
| Display name | Xanh Browser |
| Application ID | `io.github.lamppkk.xanhbrowser` |
| Version | 1.0.0 (`10000`) |
| Minimum Android | 8.0 / API 26 |
| Target Android | API 36 |
| Compile SDK | Android SDK Platform 37.1 |
| Toolchain | JDK 17, AGP 9.3.2, Gradle 9.7.1 |
| WebView compatibility layer | AndroidX WebKit 1.17.0 |

The application ID is new. Installation starts with an empty profile; legacy
Android data is not imported automatically.

## Repository layout

| Path | Purpose |
| --- | --- |
| `app/` | Native Android browser, resources, Room schemas, and tests |
| `backup-core/` | Encrypted portable-backup codec and golden vectors |
| `sync-core/` | Pinned Mozilla Accounts / Firefox Sync integration |
| `docs/` | Backup and Sync contracts plus verified preview images |
| `scripts/` | Dependency, toolchain, and native-package verification |
| `fastlane/` | Google Play listing metadata |
| `.github/workflows/` | Build, instrumentation, security, and baseline automation |
| `ADBLOCK_CORE.lock` | Content-blocker source and toolchain provenance |
| `XANH_WEBVIEW.lock` | Reviewed Xanh WebView API revision |
| `RELEASING.md` | Production release runbook and acceptance matrix |

## Xanh suite

| Repository | Role |
| --- | --- |
| [`xanh-webkit`](https://github.com/LamPPKK/xanh-webkit) | Multi-platform reference hosts, shared engine policies, and release gates |
| [`xanh-android`](https://github.com/LamPPKK/xanh-android) | This full native Android browser |
| [`xanh-ios`](https://github.com/LamPPKK/xanh-ios) | Native iPhone and iPad browser |
| [`xanh-docker`](https://github.com/LamPPKK/xanh-docker) | Containerized WPE browser runtime |
| [`xanh-tab`](https://github.com/LamPPKK/xanh-tab) | WPE appliance and tab-oriented shell |
| [`xanh-webview`](https://github.com/LamPPKK/xanh-webview) | Cross-platform embedding API and backend contract |

## Upstream and license

Xanh Android uses AndroidX and the serviced Android System WebView, Mozilla
Application Services for Sync, and Brave `adblock-rust` through the pinned Xanh
content-blocker core. Dependency licenses and notices are recorded in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and packaged app assets.

The pre-modernization Android source remains available at the
`legacy-midori-android-7.0` tag. This repository is distributed under the GNU
Lesser General Public License v3; see [LICENSE](LICENSE). Release history is in
[CHANGELOG.md](CHANGELOG.md).
