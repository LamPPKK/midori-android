# Portable encrypted backup

Xanh Browser uses a provider-neutral `.xanhbackup` snapshot instead of storing
Google Drive, operating-system cloud or Git credentials inside the browser.

## Using it

1. Choose **Export encrypted backup** and enter a unique password of at least
   eight characters.
2. Save with Android's Documents picker to Google Drive, another installed
   provider, or a local folder synchronized by the operating system or a Git
   client.
3. Let that provider finish uploading or committing the binary file.
4. Choose **Import encrypted backup** on the destination device and enter the
   same password.

Snapshots are not mergeable text. Avoid concurrent writers; when a provider
creates conflict copies, select the intended snapshot by its provider
timestamp. Keep Git repositories private and never commit the password. The
Xanh Browser source repositories ignore `*.xanhbackup`; use a separate private
sync repository for personal snapshots.

Android Auto Backup remains disabled because a browser profile can contain
cookies and tokens. Only the explicit encrypted snapshot is supported.

## Data boundary

Version 1 contains a UTC creation time, source edition, up to 50 regular
HTTP(S) tab URLs, selected-tab index and desktop-site flag. URLs containing
embedded credentials are rejected. Cookies, passwords, form data, local
storage, cache, service workers, downloads and private state are excluded.

Files are limited to 1 MiB and use this big-endian envelope:

| Field | Value |
| --- | --- |
| Magic | 8 bytes, `XANHBK1\0` |
| Envelope version | 32-bit integer, `1` |
| KDF | PBKDF2-HMAC-SHA256, 210,000 iterations, 16-byte salt |
| Encryption | AES-256-GCM, 12-byte nonce, 16-byte tag |
| Payload | versioned length-prefixed UTF-8 and binary fields |

Import fails closed for a wrong password, tampering, malformed UTF-8, unsafe
URLs, unsupported flags/versions or trailing data. Kotlin and C# implementations
share fixed ASCII and Unicode-password golden vectors to prevent format drift.
