# Changelog

## [1.0.0] - Unreleased

### Added

- Added independent multi-tab browsing with safe process-death restoration.
- Added Room-backed tabs, history, bookmarks and download records with an
  exported versioned schema.
- Added phone, tablet and foldable layouts, a browser library, desktop mode,
  sharing, file upload and per-origin geolocation consent.
- Added unit and instrumentation tests plus build, device-matrix, CodeQL and
  dependency-review GitHub Actions workflows.
- Added Google Play listing metadata and a guarded production-signing task.
- Added a scheduled, Google Maven-backed verifier that rejects stale,
  prerelease/dynamic or checksum-incomplete AndroidX WebKit dependency pins.
- Added a weekly official-metadata verifier for the newest stable AGP and
  Gradle releases, including the wrapper distribution and JAR checksums.
- Added Mozilla Accounts / Firefox Sync for bookmarks, history, remote tabs and
  an authenticated Xanh-only password vault using Application Services 155.0.
- Added idempotent Room-to-Places migration, WorkManager scheduling, OAuth
  Custom Tabs and an origin/nonce-validated WebView credential bridge.
- Added schema-v2 Places identities to the Room compatibility mirror. Bookmark
  rename/delete now targets the exact GUID, history deletion targets the exact
  URL/timestamp visit, duplicate bookmark URLs remain distinct, and failed or
  offline writes persist a migration retry intent before changing Room.
- Added checksum-verified dependencies, guarded Sync release modes and a
  documented implementation-status record for interoperability and security
  evidence.
- Added fail-closed private browsing backed by a random AndroidX WebKit profile;
  private pages never enter Room, Sync, Xanh credential filling or portable
  backup. Xanh opts the surface out of Autofill/content capture, requests no IME
  personalization and uses the same UA as regular mode.
- Bound private renderer recovery to one foreground attempt and cancel stale
  file/geolocation callbacks before destroying the failed WebView.
- Added API 29+ device instrumentation that terminates the real regular-tab
  renderer, verifies an exact replacement, and proves that a repeated crash
  reaches the stable stopped state instead of an automatic reload loop.

### Changed

- Renamed the application to Xanh Browser with application ID
  `io.github.lamppkk.xanhbrowser` and version code `10000`.
- Rebuilt the project with AGP 9.3.2, Gradle 9.7.1, JDK 17, built-in Kotlin,
  AndroidX, Material, XML Views, View Binding and Room.
- Updated the Android UI/tooling stack to stable Activity 1.13.0, Annotation
  1.10.0, AppCompat 1.8.0, Browser 1.10.0, Material Components 1.14.0 and KSP
  2.3.11.
- Migrated the Room browser database from schema 1 to schema 2 without
  discarding existing history/bookmarks; migrated rows become pending Places
  records until the idempotent importer assigns native identities.
- Raised the supported platform range to API 26–36 and added adaptive layouts.
- Moved downloads to DownloadManager and scoped storage.
- Updated the AndroidX WebKit compatibility layer to stable 1.17.0.
- Updated AndroidX Lifecycle runtime/process integration to stable 2.11.0 and
  WorkManager to stable 2.11.2.
- Updated AndroidX Core to stable 1.19.0 and moved every module to stable SDK
  Platform 37.1 for compilation while retaining target API 36.
- Hardened regular and private renderer termination handling: detach the exact
  failed WebView without calling back into it, abandon stale credential/file/
  location callbacks, recover only in foreground and stop after one attempt.

### Security

- Enabled Safe Browsing and blocked mixed content and production cleartext.
- Restricted WebView navigation to valid HTTP(S) addresses with a host and
  validated supported external intents before launch.
- Removed legacy external-storage permissions and made file/geolocation access
  user initiated.
- Added asynchronous full privacy clearing and guarded release signing.
- Added device-bound encryption for account state, biometric/device-credential
  vault unlock, five-minute/background auto-lock and Mozilla approval gates.
- Enforced one live Application Services runtime per Android process so global
  Sync engine registration cannot cross account/profile boundaries.
- Assigned the standard Android application its own
  `xanh-browser-android://accounts/oauth` callback so another Xanh edition
  cannot claim its OAuth response.
- Serialized native runtime teardown with active calls, kept failed native
  cleanup fail-closed, removed raw Places/Logins handle exposure, and made
  delete-local cleanup and password UI resilient to disconnect races.
- Restricted credential suggestions to bounded exact-origin form records,
  rejected malformed metadata, and required a recent pointer or keyboard
  trusted gesture before a page can request the native chooser.
- Applied the same exact HTTPS-origin, ASCII identifier and UTF-8 byte bounds to
  password-library list/add/update/delete/touch operations, scheduled successful
  mutations for Sync, and cleared stale decrypted UI on backgrounding or vault
  replacement.
- Isolated private cookies, cache, service workers and storage from the regular
  WebView profile and delete orphaned private profiles on process startup.

### Removed

- Removed committed IDE metadata, Kotlin synthetics, legacy support libraries
  and the previous tablet-only application structure.
- Removed legacy Android data import from the 1.0 release scope because the new
  application ID starts with a clean profile.
