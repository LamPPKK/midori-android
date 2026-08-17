package io.github.lamppkk.xanhbrowser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.core.net.toUri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Remote Firefox/Xanh tabs are displayed by device and opened only on tap. */
class RemoteTabsActivity : AppCompatActivity() {
    private data class Row(val label: String, val url: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.sync_remote_tabs)
        val list = ListView(this)
        setContentView(list)
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                SyncCoordinator.get(this@RemoteTabsActivity).runtimeOrNull()
                    ?.remoteTabs()
                    .orEmpty()
                    .flatMap { client ->
                        client.remoteTabs.mapNotNull { tab ->
                            val url = tab.urlHistory.firstOrNull()
                                ?.takeIf(::isSafeWebUrl)
                                ?: return@mapNotNull null
                            Row("${client.clientName} — ${tab.title.ifBlank { url }}", url)
                        }
                    }
            }
            if (rows.isEmpty()) {
                Toast.makeText(this@RemoteTabsActivity, R.string.sync_no_remote_tabs, Toast.LENGTH_SHORT).show()
            }
            list.adapter = ArrayAdapter(
                this@RemoteTabsActivity,
                android.R.layout.simple_list_item_1,
                rows.map(Row::label),
            )
            list.setOnItemClickListener { _, _, position, _ ->
                setResult(Activity.RESULT_OK, Intent().setData(rows[position].url.toUri()))
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun isSafeWebUrl(value: String): Boolean = runCatching {
        val uri = value.toUri()
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
