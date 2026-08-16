package io.github.lamppkk.xanhbrowser

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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

    private suspend fun awaitTabCount(database: BrowserDatabase, count: Int) {
        withTimeout(5_000) {
            while (database.tabs().getAll().size != count) delay(25)
        }
    }
}
