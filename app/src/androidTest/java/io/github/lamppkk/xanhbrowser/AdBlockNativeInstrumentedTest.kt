package io.github.lamppkk.xanhbrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdBlockNativeInstrumentedTest {
    @Test fun packagedRustCoreLoadsExactAbiAndMatchesBaseline() {
        assumeTrue(BuildConfig.XANH_ADBLOCK_NATIVE_PACKAGED)
        val matcher = NativeAdBlockMatcher.tryCreate()
        assertNotNull("The packaged Rust core did not satisfy the exact JNA ABI", matcher)
        requireNotNull(matcher)

        assertTrue(matcher.shouldBlock(request("securepubads.g.doubleclick.net")))
        assertFalse(matcher.shouldBlock(request("publisher.example")))
    }

    private fun request(host: String) = AdBlockMatchRequest(
        targetUrl = "https://$host/app.js",
        sourceUrl = "https://publisher.example/",
        targetHost = host,
        sourceHost = "publisher.example",
        resourceType = AdBlockResourceType.SCRIPT,
        method = "get",
    )
}
