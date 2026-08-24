package io.github.lamppkk.xanhbrowser

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserActivityTest {
    @Test fun launchesBrowserActivity() {
        ActivityScenario.launch(BrowserActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> check(!activity.isFinishing) }
        }
    }

    @Test fun rendererRecoveryIsForegroundAndOneShot() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = "https://example.com/renderer-recovery"
        val intent = Intent(context, BrowserActivity::class.java).setData(target.toUri())

        ActivityScenario.launch<BrowserActivity>(intent).use { scenario ->
            val firstIdentity = awaitWebViewIdentity(scenario)
            terminateRendererEventually(scenario)
            val recoveredIdentity = awaitWebViewIdentity(scenario, differentFrom = firstIdentity)
            assertNotEquals(firstIdentity, recoveredIdentity)
            assertEquals(target, awaitCurrentUrl(scenario, target))

            terminateRendererEventually(scenario)
            awaitNoActiveWebView(scenario)
        }
    }

    @Test fun recreationDoesNotDuplicateDeepLinkTab() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = BrowserDatabase.get(context)
        database.tabs().clear()
        val intent = Intent(context, BrowserActivity::class.java)
            .setData("https://example.com/deep-link".toUri())

        ActivityScenario.launch<BrowserActivity>(intent).use { scenario ->
            awaitTabCount(database, 1)
            scenario.recreate()
            awaitTabCount(database, 1)
            assertEquals("https://example.com/deep-link", database.tabs().getAll().single().url)
        }
    }

    @Test fun explicitExternalIntentIsNotStoredAsBrowserTab() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = BrowserDatabase.get(context)
        database.tabs().clear()
        val intent = Intent(context, BrowserActivity::class.java)
            .setData("mailto:hello@example.com".toUri())

        ActivityScenario.launch<BrowserActivity>(intent).use {
            awaitTabCount(database, 1)
            assertEquals(context.getString(R.string.app_website), database.tabs().getAll().single().url)
        }
    }

    @Test fun privateBrowsingNeverPersistsTabsAndQuarantinesItsProfile() = runBlocking {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = BrowserDatabase.get(context)
        database.tabs().clear()
        database.history().clear()
        val regular = BrowserRepository(database).createTab("https://regular.example")
        val typedPrivateUrl = "https://private.example/unsubmitted-secret"
        var closedProfileName = ""

        ActivityScenario.launch(PrivateBrowserActivity::class.java).use { scenario ->
            val profiles = privateProfileNames()
            assertEquals(1, profiles.size)
            closedProfileName = profiles.single()
            scenario.onActivity { it.setPrivateAddressForTest(typedPrivateUrl) }
            scenario.recreate()
            val afterRecreate = privateProfileNames()
            assertEquals(profiles, afterRecreate)
            scenario.onActivity { activity ->
                assertNotEquals(typedPrivateUrl, activity.privateAddressForTest())
                assertTrue(activity.isPrivateAutofillDisabledForTest())
                assertTrue(activity.isPrivateImeLearningDisabledForTest())
                assertTrue(
                    activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
            }
            assertEquals(listOf(regular), database.tabs().getAll())
            assertTrue(database.history().getAll().isEmpty())
        }

        val remainingProfiles = privateProfileNames()
        assertTrue(
            remainingProfiles.isEmpty() || remainingProfiles == listOf(closedProfileName),
        )
        assertFalse(database.tabs().getAll().single().url.contains("duckduckgo"))
        assertTrue(database.history().getAll().isEmpty())
    }

    private suspend fun awaitTabCount(database: BrowserDatabase, count: Int) {
        withTimeout(5_000) {
            while (database.tabs().getAll().size != count) delay(25)
        }
    }

    private fun awaitWebViewIdentity(
        scenario: ActivityScenario<BrowserActivity>,
        differentFrom: Int? = null,
    ): Int {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var identity: Int? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { identity = it.currentWebViewIdentityForTest() }
            if (identity != null && identity != differentFrom) return requireNotNull(identity)
            SystemClock.sleep(50)
        }
        return requireNotNull(identity) { "A replacement WebView was not created" }
    }

    private fun awaitCurrentUrl(
        scenario: ActivityScenario<BrowserActivity>,
        expected: String,
    ): String? {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var actual: String? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { actual = it.currentWebUrlForTest() }
            if (actual == expected) return actual
            SystemClock.sleep(50)
        }
        return actual
    }

    private fun terminateRendererEventually(scenario: ActivityScenario<BrowserActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var terminated = false
        while (!terminated && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { terminated = it.terminateCurrentRendererForTest() }
            if (!terminated) SystemClock.sleep(50)
        }
        assertTrue("WebView renderer never became available", terminated)
    }

    private fun awaitNoActiveWebView(scenario: ActivityScenario<BrowserActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var identity: Int? = 0
        while (identity != null && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { identity = it.currentWebViewIdentityForTest() }
            if (identity != null) SystemClock.sleep(50)
        }
        assertEquals(null, identity)
    }

    private fun privateProfileNames(): List<String> {
        var result = emptyList<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = ProfileStore.getInstance().allProfileNames
                .filter(PrivateProfileManager::isPrivateProfileName)
        }
        return result
    }
}
