package io.github.lamppkk.xanhbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressResolverTest {
    @Test fun keepsSecureUrl() = assertEquals("https://example.com/path", AddressResolver.resolve("https://example.com/path"))
    @Test fun defaultsHostnameToHttps() = assertEquals("https://example.com", AddressResolver.resolve("example.com"))
    @Test fun allowsLocalhostOverHttp() = assertEquals("http://localhost:8080", AddressResolver.resolve("localhost:8080"))
    @Test fun searchesText() = assertTrue(AddressResolver.resolve("xanh browser").startsWith("https://duckduckgo.com/?q=xanh%20browser"))
    @Test fun doesNotExecuteJavascript() = assertTrue(AddressResolver.resolve("javascript:alert(1)").startsWith("https://duckduckgo.com/"))
    @Test fun searchesMalformedWebUrls() {
        assertTrue(AddressResolver.resolve("https://").startsWith("https://duckduckgo.com/"))
        assertTrue(!AddressResolver.isValidWebUrl("https:///missing-host"))
    }
    @Test fun recognizesOnlyAllowlistedExternalSchemes() {
        assertTrue(AddressResolver.isExternal("mailto:hello@example.com"))
        assertTrue(!AddressResolver.isExternal("intent://malicious"))
        assertTrue(!AddressResolver.isExternal("mailto:\nhello@example.com"))
        assertTrue(!AddressResolver.isExternal("mailto:hello@example.com?subject=x%0d%0aBcc:test@example.com"))
        assertTrue(!AddressResolver.isExternal("tel:%00+84123456789"))
    }
    @Test fun incomingIntentsAcceptOnlyWebUrls() {
        assertEquals("https://example.com", AddressResolver.resolveWebIntent("https://example.com"))
        assertEquals(null, AddressResolver.resolveWebIntent("mailto:hello@example.com"))
        assertEquals(null, AddressResolver.resolveWebIntent("javascript:alert(1)"))
        assertEquals(null, AddressResolver.resolveWebIntent("https://user:secret@example.com"))
        assertEquals(null, AddressResolver.resolveWebIntent("https://example.com:70000/"))
        assertEquals(null, AddressResolver.resolveWebIntent("https://example.com/${"a".repeat(8_193)}"))
    }
}
