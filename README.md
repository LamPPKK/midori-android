# Xanh Browser for Android

Xanh Browser is the full multi-tab Android edition of the Xanh Browser family.
It uses the system WebView and native Android UI components; no browser engine
is bundled in the application.

> **Release status:** 1.0.0 is a release candidate. Local build, unit-test and
> lint lanes pass, while production remains blocked until the signed-artifact,
> emulator/device, security-scan and Play gates in
> [RELEASING.md](RELEASING.md) are complete.

## Application identity

| Property | Value |
| --- | --- |
| Display name | Xanh Browser |
| Application ID | `io.github.lamppkk.xanhbrowser` |
| Version | 1.0.0 (`10000`) |
| Minimum Android | 8.0 / API 26 |
| Compile and target SDK | API 36 |
| Java runtime | JDK 17 |
| Build system | Android Gradle Plugin 9.3, Gradle 9.5, built-in Kotlin |

This is a new application ID. Installation starts with an empty profile and
does not import data from the legacy Android application.

## Current capabilities

- Independent multi-tab browsing with safe process-death restoration
- Room-backed history, bookmarks and download records with a versioned schema
- Browser library for browsing and managing saved data
- Phone, tablet and foldable layouts using XML Views and View Binding
- Back/forward navigation, predictive back, sharing and desktop mode
- Downloads through Android DownloadManager and scoped storage
- File upload and per-origin geolocation consent through Activity Result APIs
- Checked external-scheme handoff for supported main-frame user actions
- Asynchronous privacy clearing and WebView render-process recovery
- Password-encrypted portable backup for regular tabs, compatible with the
  Lite, Android WebKit preview and Windows editions
- Optional Mozilla Accounts / Firefox Sync for bookmarks, history, remote tabs
  and an authenticated, Xanh-only password vault

## Security and privacy defaults

- Safe Browsing is enabled when supported by the installed System WebView.
- Production cleartext traffic and mixed content are blocked.
- Only valid HTTP(S) addresses with a host are loaded in the WebView.
- External schemes are resolved before launch; unsupported intents remain closed.
- File and geolocation access require user action and can be cancelled safely.
- Legacy external-storage permissions are not requested.
- Private upload keys and passwords are accepted only through environment or
  private Gradle properties and must never enter Git.
- Portable backups use PBKDF2-HMAC-SHA256 and AES-256-GCM, reject unsafe URLs
  and never contain cookies, passwords, cache or private browsing state.

JavaScript is enabled because modern websites require it. The WebView boundary,
URI validation and permission checks are therefore part of the release security
review.

## Prerequisites

- JDK 17
- Android SDK Platform 36 and Build Tools 36.0.0
- An API 26+ device or emulator for instrumentation tests

The Gradle wrapper downloads Gradle 9.5. Use the wrapper rather than a global
Gradle installation.

## Build and test

Run the same local pipeline used by the primary CI workflow:

```sh
./gradlew --no-daemon \
  :backup-core:testDebugUnitTest \
  :app:lintDebug \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleAndroidTest \
  :app:bundleRelease
```

Run connected instrumentation tests with a device or emulator available:

```sh
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

Important outputs:

| Artifact | Path | Purpose |
| --- | --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | Local installation |
| Instrumentation APK | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | Device tests |
| Release AAB | `app/build/outputs/bundle/release/app-release.aab` | Unsigned verification only |
| Lint report | `app/build/reports/lint-results-debug.html` | Static-analysis review |

`bundleRelease` does not produce a Play-uploadable artifact without signing
configuration. Follow [RELEASING.md](RELEASING.md) and use the guarded
`bundleProductionRelease` task for production.

## Encrypted backup and provider sync

Use **Export encrypted backup** and **Import encrypted backup** in the browser
menu. The operating-system Documents picker can write the `.xanhbackup` file
directly to Google Drive or another installed provider. The same encrypted
snapshot can be stored in an OS-backed-up folder or a Git working tree without
giving Xanh Browser any cloud or Git credential.

The full Android edition exports up to 50 regular open tabs and restores them
as new tabs. Cookies, passwords, form data, cache, downloads and private state
are excluded. See [`docs/PORTABLE_BACKUP.md`](docs/PORTABLE_BACKUP.md) for the
wire format and conflict rules.

## Android WebKit preview

The separately installable WPE WebKit experiment is intentionally the Lite
one-tab edition in the
[core repository](https://github.com/LamPPKK/midori-core/tree/master/app-webkit).
The full multi-tab app remains on serviced Android System WebView until WPEView
exposes the navigation, permission, download and 16 KiB-native-library baseline
required by the full browser. The preview uses its own application ID and
cannot silently replace this production edition.

## Mozilla Accounts / Firefox Sync

The `sync-core` module consumes the official Mozilla Application Services
155.0 AARs and provides OAuth PKCE, Places/Tabs/Logins engines, WorkManager
scheduling, encrypted account state and the native credential boundary. It can
use an approved Mozilla-hosted client or an HTTPS self-hosted deployment. See
[`docs/FIREFOX_SYNC.md`](docs/FIREFOX_SYNC.md) for data migration, security and
release requirements. Account tokens, scoped keys and passwords are never part
of `.xanhbackup`.

## Project structure

| Path | Purpose |
| --- | --- |
| `app/src/main/.../BrowserActivity.kt` | Main browser UI and WebView lifecycle |
| `app/src/main/.../ActivityTabs.kt` | Tab overview and tab actions |
| `app/src/main/.../BrowserDatabase.kt` | Room entities, DAOs and schema |
| `app/src/main/.../BrowserRepository.kt` | Persistence and session operations |
| `app/src/main/.../LibraryActivity.kt` | History, bookmark and download library |
| `app/src/main/.../AddressResolver.kt` | Validated address and search resolution |
| `backup-core/` | Portable encrypted backup codec and cross-platform vectors |
| `sync-core/` | Pinned Mozilla Application Services Android integration |
| `app/schemas/` | Exported Room migration schema |
| `app/src/test/` | Local unit tests |
| `app/src/androidTest/` | Activity and database instrumentation tests |
| `fastlane/` | Google Play listing metadata |
| `.github/workflows/` | Build, device-matrix and CodeQL automation |

## Continuous integration

GitHub Actions builds and lints every push and pull request, runs CodeQL, blocks
high-severity dependency-review findings and compiles Android test artifacts.
A scheduled instrumentation matrix targets API 26, 30, 33 and 36 with phone,
tablet and foldable profiles. CI AABs are unsigned verification artifacts.

## Release scope

Xanh Browser 1.0 supports Android API 26–36. A legacy-data bridge and a bundled
WebKit engine for the full edition are not part of this repository's 1.0
production scope. Xanh Browser Lite, its WebKit preview and the Linux desktop
application are maintained in the
[core repository](https://github.com/LamPPKK/midori-core).

## Historical baseline and license

The pre-modernization Android source is preserved by the
`legacy-midori-android-7.0` tag. See [LICENSE](LICENSE) for license terms and
[CHANGELOG.md](CHANGELOG.md) for release history.
