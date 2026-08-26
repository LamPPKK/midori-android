# Third-party notices

## Brave adblock-rust 0.13.3

The production content blocker uses Brave `adblock-rust` 0.13.3 through Xanh's
`xanh-adblock-core` C ABI. The reviewed upstream revision is
`886d45dcf5283ce8eddc6d961e7dd27966ab23f2`. `adblock-rust` is licensed under
the Mozilla Public License 2.0 (MPL-2.0).

- Source: <https://github.com/brave/adblock-rust>
- Release: <https://github.com/brave/adblock-rust/releases/tag/v0.13.3>
- License: <https://www.mozilla.org/MPL/2.0/>

The Android artifact is built from the `xanh-webkit` revision recorded in
`ADBLOCK_CORE.lock`; its generated `ADBLOCK_CORE.manifest` records that revision
and the SHA-256 digest for every packaged ABI. Distribution must retain this
notice, the source location and the corresponding MPL-2.0 license text. The
seven-rule Android fallback is Xanh-owned and does not copy EasyList or uBlock
Origin filter lists.

## Java Native Access (JNA) 5.18.1

Xanh Browser directly uses the JNA 5.18.1 Android AAR to call the reviewed
`xanh-adblock-core` C ABI. JNA is dual-licensed under LGPL-2.1-or-later or
Apache-2.0; Xanh distributes it under Apache-2.0.

- Source: <https://github.com/java-native-access/jna>
- Release: <https://github.com/java-native-access/jna/releases/tag/5.18.1>
- Apache-2.0 license: <https://www.apache.org/licenses/LICENSE-2.0>

## Mozilla Application Services 155.0

Xanh Browser uses Mozilla Application Services 155.0, released from revision
`c0fd8cea40c9b5dafc6604831f7bd7a8c096d313`, for Mozilla Accounts and Firefox
Sync interoperability. Application Services is licensed under the Mozilla
Public License 2.0 (MPL-2.0).

- Source: <https://github.com/mozilla/application-services>
- Release: <https://github.com/mozilla/application-services/releases/tag/v155.0>
- License: <https://www.mozilla.org/MPL/2.0/>

Xanh Browser links the official Maven artifacts and does not modify upstream
Application Services source. Distribution must retain this notice, the
corresponding source location and the release SBOM. Any future modification of
MPL-covered files must be published in source form under MPL-2.0.

The artifacts include Mozilla Glean, NSS and other transitive components.
Their versions and license data are recorded in the generated dependency graph
and release SBOM. Xanh Browser Sync v1 does not send product telemetry.
