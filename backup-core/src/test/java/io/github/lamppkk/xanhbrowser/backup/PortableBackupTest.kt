package io.github.lamppkk.xanhbrowser.backup

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableBackupTest {
    private val payload = PortableBackupPayload(
        createdAtEpochMillis = 1_700_000_000_000,
        sourceEdition = "android-lite-webkit",
        urls = listOf("https://example.com/", "https://webkit.org/"),
        selectedIndex = 1,
        desktopSite = true,
    )

    @Test
    fun encryptedBackupRoundTrips() {
        val password = "correct horse battery staple".toCharArray()
        val encoded = PortableBackup.encode(payload, password)
        assertEquals(payload, PortableBackup.decode(encoded, password))
    }

    @Test
    fun matchesPortableGoldenVectors() {
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 16).toByte() }
        val ascii = PortableBackup.encode(
            payload,
            "correct horse battery staple".toCharArray(),
            salt,
            nonce,
        )
        assertEquals(
            "WEFOSEJLMQAAAAABAAM0UAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhsAAABrGe+1+LCSaxIIZoTehQCW/YIh3t1cWKdtm2ZRhz5KDI1ss8rhvNIL709BNuA0TcxI/6UIxeKecxM6+ofiMw3m0Ij51SZIl9KKSASqMcyXCRVDgSnVBCrcAKURj7URBqlSOW181AoYQXA+Aiw=",
            Base64.getEncoder().encodeToString(ascii),
        )

        val unicode = PortableBackup.encode(
            payload,
            "mật-khẩu-Xanh-🔒".toCharArray(),
            salt,
            nonce,
        )
        assertEquals(
            "WEFOSEJLMQAAAAABAAM0UAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhsAAABrfjICD8Mjv9vFJ0zWrTRPQSUTvi8TnvUo1avIYeQ5ehuR/ZrSJ6l/mE8DpJJ/RyRFfKbZF6I+GbB2zRIFF8M//fuYuJSK/3/0K0dBM0CnXITV+mXV4Z6zOoIAxLCvs/QXPJTSiUKgBg/XtVE=",
            Base64.getEncoder().encodeToString(unicode),
        )
    }

    @Test
    fun rejectsWrongPasswordTamperingAndUnsafeUrls() {
        val encoded = PortableBackup.encode(payload, "correct password".toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackup.decode(encoded, "wrong password".toCharArray())
        }
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            PortableBackup.decode(encoded, "correct password".toCharArray())
        }
        listOf("file:///private/data", "https://user:secret@example.com/").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                PortableBackup.encode(
                    payload.copy(urls = listOf(url), selectedIndex = 0),
                    "correct password".toCharArray(),
                )
            }
        }
    }
}
