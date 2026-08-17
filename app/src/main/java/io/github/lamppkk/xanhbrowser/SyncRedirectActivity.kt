package io.github.lamppkk.xanhbrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                intent.data?.let { runCatching { SyncCoordinator.get(this@SyncRedirectActivity).completeOAuth(it) } }
            }
            startActivity(
                Intent(this@SyncRedirectActivity, SyncSettingsActivity::class.java)
                    .putExtra(SyncSettingsActivity.EXTRA_AUTH_SUCCESS, result?.isSuccess == true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }
}
