package io.github.lamppkk.xanhbrowser

import android.content.Context
import android.content.Intent
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

    @Test fun privateBrowsingNeverPersistsTabsAndDeletesItsProfile() = runBlocking {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = BrowserDatabase.get(context)
        database.tabs().clear()
        database.history().clear()
        val regular = BrowserRepository(database).createTab("https://regular.example")
        val typedPrivateUrl = "https://private.example/unsubmitted-secret"

        ActivityScenario.launch(PrivateBrowserActivity::class.java).use { scenario ->
            val profiles = privateProfileNames()
            assertEquals(1, profiles.size)
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

        withTimeout(5_000) {
            while (privateProfileNames().isNotEmpty()) {
                delay(25)
            }
        }
        assertFalse(database.tabs().getAll().single().url.contains("duckduckgo"))
        assertTrue(database.history().getAll().isEmpty())
    }

    private suspend fun awaitTabCount(database: BrowserDatabase, count: Int) {
        withTimeout(5_000) {
            while (database.tabs().getAll().size != count) delay(25)
        }
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
