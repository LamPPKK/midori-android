# Third-party notices

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

The artifacts include Mozilla Glean, JNA, NSS and other transitive components.
Their versions and license data are recorded in the generated dependency graph
and release SBOM. Xanh Browser Sync v1 does not send product telemetry.
