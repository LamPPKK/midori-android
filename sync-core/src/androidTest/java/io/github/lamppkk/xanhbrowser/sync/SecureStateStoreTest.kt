package io.github.lamppkk.xanhbrowser.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureStateStoreTest {
    @Test
    fun accountStateIsDeviceEncryptedAndCanBeDestroyed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SecureStateStore(context)
        val secret = "test-token-that-must-not-be-plaintext"
        store.put("instrumentation", secret)

        val raw = context.getSharedPreferences("xanh_sync_secure_state", Context.MODE_PRIVATE)
            .all.values.joinToString()
        assertFalse(raw.contains(secret))
        assertEquals(secret, store.get("instrumentation"))

        store.clear()
        assertNull(store.get("instrumentation"))
    }

    @Test
    fun deleteLocalDataRemovesEverySyncDatabaseEvenWithoutARuntime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val names = listOf("xanh-places.sqlite", "xanh-tabs.sqlite", "xanh-logins.sqlite")
        names.forEach { context.openOrCreateDatabase(it, Context.MODE_PRIVATE, null).close() }
        SecureStateStore(context).put("instrumentation", "encrypted-account-state")

        XanhSyncRuntime.deleteLocalData(context)

        names.forEach { assertFalse(context.getDatabasePath(it).exists()) }
        assertNull(SecureStateStore(context).get("instrumentation"))
    }
}
