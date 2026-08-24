package io.github.lamppkk.xanhbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockCoordinatorTest {
    @Test fun `content blocking defaults on`() {
        assertTrue(AdBlockCoordinator.DEFAULT_ENABLED)
    }

    @Test fun `native ABI version must match exactly`() {
        val exact = (NativeAdBlockMatcher.EXPECTED_VERSION + "\u0000").toByteArray()
        assertTrue(NativeAdBlockMatcher.acceptsVersionBytes(exact))
        assertFalse(
            NativeAdBlockMatcher.acceptsVersionBytes(
                (NativeAdBlockMatcher.EXPECTED_VERSION + ".1\u0000").toByteArray(),
            ),
        )
    }

    @Test fun `main frame is always allowed before matcher invocation`() {
        assertTrue(AdBlockRequestPolicy.bypassBeforeHost(isForMainFrame = true))
        assertFalse(AdBlockRequestPolicy.bypassBeforeHost(isForMainFrame = false))
        assertTrue(AdBlockRequestPolicy.isSupportedWebScheme("https"))
        assertTrue(AdBlockRequestPolicy.isSupportedWebScheme("HTTP"))
        assertFalse(AdBlockRequestPolicy.isSupportedWebScheme("content"))
        assertFalse(AdBlockRequestPolicy.isSupportedWebScheme(null))
        var invoked = false
        val matcher = AdBlockMatcher { invoked = true; true }
        val host = AdBlockHost(
            enabled = { true },
            fallbackMatcher = matcher,
        )

        assertFalse(
            host.shouldBlock(
                targetUrl = "https://doubleclick.net/page",
                sourceUrl = "https://example.com/",
                isForMainFrame = true,
                method = "GET",
                headers = emptyMap(),
            ),
        )
        assertFalse(invoked)
    }

    @Test fun `request inference prefers fetch destination and normalizes method`() {
        val request = AdBlockRequestPolicy.create(
            targetUrl = "https://cdn.example/opaque",
            sourceUrl = "https://example.com/page",
            isForMainFrame = false,
            method = "GET",
            headers = mapOf("sec-fetch-dest" to "script", "Accept" to "*/*"),
        )

        requireNotNull(request)
        assertEquals(AdBlockResourceType.SCRIPT, request.resourceType)
        assertEquals("get", request.method)
        assertEquals("cdn.example", request.targetHost)
        assertEquals("https://example.com/page", request.sourceUrl)
        assertEquals("example.com", request.sourceHost)
    }

    @Test fun `request inference falls back to accept then extension`() {
        val image = AdBlockRequestPolicy.create(
            "https://cdn.example/no-extension",
            null,
            false,
            "GET",
            mapOf("ACCEPT" to "image/avif,image/webp,*/*"),
        )
        val stylesheet = AdBlockRequestPolicy.create(
            "https://cdn.example/site.CSS?version=2",
            null,
            false,
            "GET",
            emptyMap(),
        )

        assertEquals(AdBlockResourceType.IMAGE, image?.resourceType)
        assertEquals("https://cdn.example/no-extension", image?.sourceUrl)
        assertEquals("cdn.example", image?.sourceHost)
        assertEquals(AdBlockResourceType.STYLESHEET, stylesheet?.resourceType)
    }

    @Test fun `oversized target stays outside native boundary and invalid source becomes target`() {
        val oversized = "https://example.com/" + "a".repeat(AdBlockRequestPolicy.MAX_URL_BYTES)
        assertNull(AdBlockRequestPolicy.create(oversized, null, false, "GET", emptyMap()))
        assertTrue(AdBlockRequestPolicy.exceedsNativeUrlLimit(oversized))
        assertEquals("doubleclick.net", AdBlockRequestPolicy.normalizeFallbackHost("DoubleClick.NET."))

        val safeTarget = AdBlockRequestPolicy.create(
            "https://doubleclick.net/ad.js",
            oversized,
            false,
            "GET",
            emptyMap(),
        )
        assertEquals("https://doubleclick.net/ad.js", safeTarget?.sourceUrl)
        assertEquals("doubleclick.net", safeTarget?.sourceHost)
    }

    @Test fun `disabled and failed matchers fail open`() {
        val allowFallback = AdBlockMatcher { false }
        val disabled = AdBlockHost({ false }, allowFallback, AdBlockMatcher { true })
        val unavailableNative = AdBlockHost(
            { true },
            allowFallback,
            AdBlockMatcher { throw UnsatisfiedLinkError("missing") },
        )

        val input = arrayOf(
            "https://doubleclick.net/ad.js",
            "https://example.com/",
        )
        assertFalse(disabled.shouldBlock(input[0], input[1], false, "GET", emptyMap()))
        assertFalse(unavailableNative.shouldBlock(input[0], input[1], false, "GET", emptyMap()))
    }

    @Test fun `failed native matcher retains bounded fallback protection`() {
        val fallback = BundledAbpDomainMatcher.fromText("||doubleclick.net^")
        val host = AdBlockHost(
            { true },
            fallback,
            AdBlockMatcher { throw IllegalStateException("native failure") },
        )

        assertTrue(
            host.shouldBlock(
                "https://doubleclick.net/advert.js",
                "https://publisher.example/",
                false,
                "GET",
                emptyMap(),
            ),
        )
    }

    @Test fun `oversized tracker still matches the host fallback`() {
        val fallback = BundledAbpDomainMatcher.fromText("||doubleclick.net^")
        val oversized = "https://doubleclick.net/ad.js?" +
            "a".repeat(AdBlockRequestPolicy.MAX_URL_BYTES)

        assertTrue(AdBlockRequestPolicy.exceedsNativeUrlLimit(oversized))
        assertTrue(fallback.shouldBlockHosts("doubleclick.net", "publisher.example"))
    }

    @Test fun `bounded domain matcher blocks exact and subdomains but honors exception`() {
        val matcher = BundledAbpDomainMatcher.fromText(
            """
            ||doubleclick.net^
            ||ads.example^
            @@||allowed.ads.example^
            """.trimIndent(),
        )

        assertTrue(matcher.shouldBlock(request("doubleclick.net")))
        assertTrue(matcher.shouldBlock(request("media.doubleclick.net")))
        assertTrue(matcher.shouldBlock(request("ads.example")))
        assertFalse(matcher.shouldBlock(request("allowed.ads.example")))
        assertFalse(matcher.shouldBlock(request("notdoubleclick.net")))
    }

    @Test fun `third party domain rule needs a valid cross-site source`() {
        val matcher = BundledAbpDomainMatcher.fromText(
            "||connect.facebook.net^\$third-party",
        )

        assertTrue(matcher.shouldBlock(request("connect.facebook.net", "publisher.example")))
        assertFalse(matcher.shouldBlock(request("connect.facebook.net", "www.facebook.net")))
        assertFalse(matcher.shouldBlock(request("connect.facebook.net", null)))
    }

    private fun request(host: String, sourceHost: String? = "publisher.example"): AdBlockMatchRequest {
        val normalizedSourceHost = sourceHost ?: host
        return AdBlockMatchRequest(
            targetUrl = "https://$host/ad.js",
            sourceUrl = "https://$normalizedSourceHost/",
            targetHost = host,
            sourceHost = normalizedSourceHost,
            resourceType = AdBlockResourceType.SCRIPT,
            method = "get",
        )
    }
}
