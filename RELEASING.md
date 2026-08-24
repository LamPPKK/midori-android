# Xanh Browser Android 1.0 release runbook

This runbook produces the full Android edition. Do not upload an artifact from
the ordinary `bundleRelease` task: without private signing configuration it is
an unsigned verification bundle.

## Release identity

| Property | Required value |
| --- | --- |
| Display name | Xanh Browser |
| Application ID | `io.github.lamppkk.xanhbrowser` |
| Version name | `1.0.0` |
| Version code | `10000` |
| Minimum SDK | 26 |
| Target SDK | 36 |

The archival baseline is `legacy-midori-android-7.0`. This application ID is
new and intentionally starts with an empty profile.

## Prerequisites

- Push access and access to GitHub Actions, CodeQL and dependency-review results
- JDK 17, Android SDK Platform 37.1, Build Tools 36.1.0, AGP 9.3.2 and the
  checksum-verified Gradle 9.7.1 wrapper
- API 26, 30, 33 and 36 devices/emulators, including phone, tablet and foldable
- Multiple supported stable System WebView versions for compatibility checks
- A System WebView exposing AndroidX `MULTI_PROFILE` for private-mode
  acceptance; unsupported providers must show the fail-closed message
- Access to the dedicated Google Play listing, internal/closed tracks and
  pre-launch report
- A dedicated upload key enrolled in Play App Signing
- A registered Firefox Accounts client ID and redirect URI for this Android
  application (`xanh-browser-android://accounts/oauth`), or an explicitly
  documented self-hosted-only release
- These four values supplied through the environment or matching private Gradle
  properties

| Environment variable | Purpose |
| --- | --- |
| `XANH_ANDROID_KEYSTORE` | Upload-key keystore path |
| `XANH_ANDROID_STORE_PASSWORD` | Keystore password |
| `XANH_ANDROID_KEY_ALIAS` | Upload-key alias |
| `XANH_ANDROID_KEY_PASSWORD` | Upload-key password |

Never store keystores, passwords or exported secret values in the repository,
Gradle project files, build logs or release attachments.

## 1. Prepare the candidate

1. Confirm the worktree is clean and the intended release commit is pushed.
2. Confirm `versionName`, `versionCode`, `applicationId`, `minSdk` and
   `targetSdk` in `app/build.gradle`.
3. Confirm the Play listing and changelog under `fastlane/metadata/android/`.
4. Require green Android, CodeQL and dependency-review workflows for the exact
   candidate commit.
5. Run `python3 scripts/verify_android_toolchain_latest.py` with network access
   and confirm the root plugin pins, wrapper distribution/checksum and wrapper
   JAR match the newest official stable AGP and Gradle releases. Prereleases do
   not satisfy this production gate.
6. Run `python3 scripts/verify_androidx_webkit_latest.py` with network access
   and confirm the Gradle pin plus strict dependency checksums match the newest
   stable release in official Google Maven metadata. Prereleases never satisfy
   this production gate.

Verification:

```sh
git status --short
git grep -n -E 'org\.midori|com\.midori|io\.github\.midori'
```

The first command must print nothing. The second must not match a shipping
application ID or user-facing product string.

## 2. Run local verification

```sh
./gradlew --no-daemon clean
./gradlew --no-daemon \
  :backup-core:testDebugUnitTest \
  :sync-core:lintDebug \
  :sync-core:testDebugUnitTest \
  :sync-core:assembleDebug \
  :app:lintDebug \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :app:assembleAndroidTest \
  :app:bundleRelease
```

Verification:

- Gradle exits successfully.
- Unit tests contain no failure or skipped blocker.
- Android lint contains no error or high-severity finding.
- Debug, instrumentation and release-bundle outputs exist under
  `app/build/outputs/`.
- The generated release manifest has the required ID, version and API levels,
  no legacy storage permission, production cleartext disabled and Safe Browsing
  metadata enabled.

## 3. Run the device and WebView matrix

Run `connectedDebugAndroidTest` on:

- API 26 phone
- API 30 phone
- API 33 phone
- API 36 phone
- API 36 tablet
- API 36 foldable

Repeat the release-critical scenarios with multiple stable System WebView
versions. At minimum, verify navigation, back/forward and predictive back,
rotation, process death, multi-tab recovery, downloads, sharing, external
schemes, file upload, per-origin geolocation consent, privacy clearing and an
encrypted backup round-trip through Google Drive or another Documents provider.
For a regular tab, force renderer termination in foreground and background;
confirm exact-view teardown, stale credential/file/location cancellation, no
background reload, one automatic recovery at most, and a stable stopped state
after a repeated crash.
`BrowserActivityTest.rendererRecoveryIsForegroundAndOneShot` automates the
foreground kill/replacement/repeated-crash path with the real WebView renderer
on API 29 and newer; the background and multi-provider cases remain mandatory
manual matrix checks.
Open private browsing with a provider that supports `MULTI_PROFILE`, verify its
cookies/storage are invisible to regular tabs, rotate the Activity, close it,
and confirm the random profile is deleted. Kill the process mid-session and
confirm the next cold start deletes the orphaned profile before opening a page.
Repeat once with an unsupported provider and confirm no private page is loaded
through the default profile. Confirm private URLs never enter Room, history,
Sync, framework saved state, Xanh credential suggestions or `.xanhbackup`.
Inspect the private hierarchy flags that opt out of Autofill/content capture and
request no IME personalization, while treating external provider compliance as
platform-controlled; confirm the regular and private user agents match.
Force renderer termination in foreground and background: the dead WebView and
stale file/location callbacks must be destroyed immediately, at most one
foreground recovery may run, and a second failure must close rather than loop.
The only automatic persistent private-session output from Xanh is a
native-confirmed download; verify the confirmation appears before enqueue and
clearly covers both the file and the persistent system DownloadManager
record/source metadata. User-initiated Share and external-app handoffs disclose
their selected URL to the chosen target, which controls its own retention.

Import the same snapshot in Android Lite and Windows, and decode their shared
golden vector locally. Confirm that only regular HTTP(S) tab URLs and the
selected-tab/desktop setting cross platforms; cookies, passwords, cache and
private state must never appear.

Verification: all instrumentation tests and the manual browser checklist pass;
no tab is duplicated after deep-link recreation, no explicit external scheme is
loaded in the WebView, and no private/cleared data returns after restart.

For Firefox Places, install a schema-1 database and verify migration to schema
2 preserves every row with an empty pending identity. After Sync, verify the
Room mirror retains each bookmark GUID and each visit's exact timestamp/remote
bit, including two bookmarks with the same URL. Rename one duplicate bookmark
and delete the other; Firefox must receive changes for only those GUIDs and URL,
folder and position must remain unchanged. Delete one of two same-URL history
visits and verify only the selected timestamp receives a tombstone. Repeat
rename/delete while Places is unavailable, restart at the marker/Room boundary,
and confirm the next Sync imports the pending result without restoring the old
row. A malformed or stale GUID must fail closed without deleting by URL.

For the Xanh-only password library, unlock with device authentication and test
list/add/update/confirm-delete plus a successful credential fill/touch. Each
successful mutation must schedule local-change Sync. Reject HTTP, userinfo,
path/query/fragment-bearing origins, invalid ports/STD3 hostnames, HTTP-auth or
cross-origin form records, non-ASCII/overlong IDs, embedded NUL/control values,
usernames over 1,024 UTF-8 bytes, passwords over 4,096 UTF-8 bytes and field
names over 256 UTF-8 bytes without dismissing or retargeting the editor. Move
the app to background, lock the vault, disconnect and replace the runtime while
queries/mutations are pending; decrypted rows and dialogs must disappear and a
stale completion must not repopulate UI. Confirm the secure window blocks
screenshots and the hierarchy opts out of Autofill/content capture and requests
no IME personalized learning, while treating external provider/IME compliance
as platform-controlled. This library must not register Xanh as an OS-wide
Autofill service.

## 4. Build the signed production candidate

Before packaging Sync, complete the Firefox↔Xanh interoperability suite and
redaction/security review described in `docs/FIREFOX_SYNC.md`. For an approved
Mozilla-hosted build export all four `XANH_ANDROID_*` values plus:

```sh
export XANH_SYNC_MOZILLA_HOSTED=1
export XANH_FXA_CLIENT_ID='<registered Android client ID>'
export XANH_FXA_PRODUCTION_APPROVED=1
```

If Mozilla production access was not approved, publish only the documented
self-hosted mode with `XANH_SYNC_SELF_HOSTED_ONLY=1`; do not set the approval
flag or advertise Mozilla-hosted compatibility. Then run:

```sh
./gradlew --no-daemon bundleProductionRelease
```

Verification:

- `verifyReleaseSigning` succeeds.
- `verifyFirefoxSyncRelease` succeeds for the explicitly selected server mode.
- `app/build/outputs/bundle/release/app-release.aab` is signed by the dedicated
  Xanh Browser upload key.
- The AAB reports `io.github.lamppkk.xanhbrowser`, `1.0.0`, code `10000`, minimum
  API 26 and target API 36.
- An APK generated by Google Play from that AAB installs and launches on a clean
  API 26 device and a clean API 36 device.

If the signing guard fails, fix the private environment configuration. Do not
weaken or bypass `verifyReleaseSigning`.

## 5. Promote through Google Play

1. Upload to internal testing and resolve all processing errors.
2. Review the Play pre-launch report and resolve every blocker/high finding.
3. Complete internal functional and privacy testing.
4. Promote the same artifact to closed testing and collect the required test
   feedback.
5. Start a staged production rollout only after the full and Lite Android
   editions and the Linux Flatpak have met their coordinated release gates.
6. Monitor crashes, ANRs, WebView compatibility and user reports before raising
   the rollout percentage.

Verification: the Play Console shows the intended app ID, version and signing
certificate; the production artifact is the exact AAB approved in internal and
closed testing.

## Final checklist

- CI, CodeQL, dependency review, lint and Play pre-launch have no blocker/high.
- The Application Services pin/notice/SBOM is present and four-engine
  Firefox↔Xanh interoperability passes without local data loss.
- Private data never enters Sync; the credential vault auto-locks on background
  and after five minutes; database/log/crash inspection finds no secrets.
- All required device/WebView scenarios pass.
- Private mode passes profile isolation, close/restart cleanup and unsupported-
  provider fail-closed tests without persisting a URL to Room or Sync.
- Portable backup unit vectors and Android/Lite/Windows provider round-trips pass.
- A signed Play-generated install works from a clean profile.
- Store metadata uses Xanh Browser consistently and does not promise a legacy
  data bridge.
- Xanh Browser uses a signing key and Play listing separate from Xanh Browser Lite.

## Rollback and escalation

- Before production, reject the candidate, fix it and restart at step 1.
- During a staged rollout, halt the rollout immediately when a release blocker
  appears. Fix forward with a higher version code; Play version codes cannot be
  reused after publication.
- If the rollout has completed, publish a tested hotfix with a higher version
  code. Do not attempt to replace the existing AAB in place.
- If an upload key or password may be exposed, stop all uploads, rotate/reset the
  upload key through Play support, remove the secret from CI and audit Git
  history and logs before resuming.
