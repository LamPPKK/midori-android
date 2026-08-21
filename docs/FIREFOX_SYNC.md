# Mozilla Accounts / Firefox Sync

Xanh Browser integrates the official Mozilla Application Services libraries;
it does not reimplement Sync 1.5. The Android build pins the five AARs used by
the app (`fxaclient`, `places`, `syncmanager`, `logins`, and `tabs`) to stable
version 155.0. The matching source revision and license are recorded in
`sync-core/APPLICATION_SERVICES.lock`.
Gradle verifies the downloaded module metadata and binaries against SHA-256
entries in `gradle/verification-metadata.xml`; changing the pin requires a
reviewed metadata regeneration and a fresh SBOM/security scan.

The module has its own coordinate and version,
`io.github.lamppkk.xanhbrowser:xanh-sync-android:1.0.0-alpha.1`. CI emits a
release AAR and local Maven repository as verification artifacts. Lite must
consume a reviewed, checksum-pinned publication of that AAR from its on-demand
feature; it must not copy the Rust runtime into the base APK.

## Account and server modes

OAuth is opened in an Android Custom Tab. PKCE and OAuth state are managed by
`FxaClient`; a Mozilla password is never entered into Xanh's WebView. The
registered redirect URI for this edition is
`xanh-browser://accounts/oauth`.

Mozilla-hosted mode requires a production client ID and Mozilla approval.
Self-hosted mode requires an HTTPS Accounts URL, an HTTPS Token Server URL and
a client ID issued by that deployment. Xanh displays the Accounts domain before
opening OAuth and does not provide a TLS bypass.

## Data ownership and migration

Places owns synchronized bookmarks and history; Tabs and Logins own their
respective synchronized collections. The existing Room bookmark/history tables
remain a one-release compatibility and rollback source. Before first import,
Xanh writes a local logical snapshot in app-private, no-backup storage, imports
idempotently and records the verified counts and SHA-256 marker. Open tabs and
downloads remain in Room. Remote tabs are listed by device and are never opened
automatically.

All four engines are enabled on a device by default. A device switch only
changes that device's selection. WorkManager handles connected background
work; foreground work is limited to once per 15 minutes, local changes are
debounced for 30 seconds, sync is single-flight and server backoff always wins.

Application Services 155 keeps its Sync engine registrations process-wide.
Android therefore permits exactly one live Xanh Sync runtime per process: a
second profile fails before registering any engine, and the lease is released
only after all native stores close successfully. This prevents an account from
resolving another profile's Places, Tabs or Logins engine.

## Password boundary

Account state and Sync metadata are encrypted with an Android Keystore key.
The Logins database uses a separate random key wrapped by a
user-authentication-required Keystore key. The vault locks after five minutes
or whenever the app leaves the foreground.

The WebView bridge uses an AndroidX WebKit document-start script and validates
HTTPS origin, top frame, tab ID, navigation nonce and message type. A renderer
can only request a native credential chooser. Filling requires an explicit user
selection, an unlocked vault and the exact current HTTPS origin. Private mode,
HTTP, URL userinfo and cross-origin frames are denied.

Tokens, scoped keys, account state and passwords are excluded from logs and
`.xanhbackup`. “Delete from this device” removes encrypted account state, sync
metadata, Places/Logins/Tabs data, migration backup and both Keystore keys.

## Release gates

`bundleProductionRelease` requires signing plus one of these explicit modes:

- `XANH_SYNC_MOZILLA_HOSTED=1`, `XANH_FXA_CLIENT_ID`, and
  `XANH_FXA_PRODUCTION_APPROVED=1`; or
- `XANH_SYNC_SELF_HOSTED_ONLY=1`, with the server/client configuration supplied
  to users by their deployment administrator.

Production also requires the Firefox↔Xanh interop matrix, device tests,
redaction review, FFI/message fuzzing, an SBOM and an independent security
review. Credentials used by staging tests must never be stored in Git or CI
logs.

## Implementation snapshot (2026-08-22)

The Android implementation builds against the checksum-verified Application
Services 155.0 AARs. Local verification currently passes 21 JVM tests across
the backup, Sync and browser modules; Android lint completes without errors;
and the app plus Sync instrumentation APKs compile. The production bundle task
remains guarded by explicit Sync mode, Mozilla approval and signing inputs.

The verified implementation includes OAuth Custom Tabs, encrypted account
state, authenticated vault unlock and background lock, idempotent
Room-to-Places migration, all four Sync engines, WorkManager scheduling,
remote-tab presentation and an AndroidX WebKit document-start credential
bridge with exact origin, tab and navigation-nonce checks. “Delete from this
device” removes local engine databases even when account restoration fails.
Runtime use and close are serialized, native Places/Logins handles never
escape the wrapper, failed engine registration retains the process lease
fail-closed, and a retained password screen exits safely if another flow
disconnects or replaces its runtime.

The following evidence is deliberately not claimed by the repository yet:

- Mozilla production client ID/redirect approval or production self-hosted
  deployment credentials;
- live Firefox-to-Xanh create/update/delete and conflict testing with a
  disposable account;
- executed instrumentation on API 26, 30, 33 and 36 across phone, tablet,
  foldable and multiple System WebView versions;
- signed, installed release artifacts and Play pre-launch results;
- message/FFI fuzzing, secret-redaction review and an independent security
  review.

CI artifacts are verification outputs, not Play upload artifacts. Production
remains blocked until the release commit has green remote CI and every item in
the interoperability and security matrix has recorded evidence.
