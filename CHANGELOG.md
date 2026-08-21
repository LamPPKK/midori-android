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
- Added Mozilla Accounts / Firefox Sync for bookmarks, history, remote tabs and
  an authenticated Xanh-only password vault using Application Services 155.0.
- Added idempotent Room-to-Places migration, WorkManager scheduling, OAuth
  Custom Tabs and an origin/nonce-validated WebView credential bridge.
- Added checksum-verified dependencies, guarded Sync release modes and a
  documented implementation-status record for interoperability and security
  evidence.

### Changed

- Renamed the application to Xanh Browser with application ID
  `io.github.lamppkk.xanhbrowser` and version code `10000`.
- Rebuilt the project with AGP 9.3, Gradle 9.5, JDK 17, built-in Kotlin,
  AndroidX, Material, XML Views, View Binding and Room.
- Raised the supported platform range to API 26–36 and added adaptive layouts.
- Moved downloads to DownloadManager and scoped storage.

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
- Serialized native runtime teardown with active calls, kept failed native
  cleanup fail-closed, removed raw Places/Logins handle exposure, and made
  delete-local cleanup and password UI resilient to disconnect races.

### Removed

- Removed committed IDE metadata, Kotlin synthetics, legacy support libraries
  and the previous tablet-only application structure.
- Removed legacy Android data import from the 1.0 release scope because the new
  application ID starts with a clean profile.
