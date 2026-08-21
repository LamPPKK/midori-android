package io.github.lamppkk.xanhbrowser

import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lamppkk.xanhbrowser.sync.XanhSyncRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.appservices.logins.Login
import mozilla.appservices.logins.LoginEntry
import java.net.URI

/** Xanh-only password manager; it does not register as an OS autofill service. */
class PasswordManagerActivity : AppCompatActivity() {
    private lateinit var runtime: XanhSyncRuntime
    private lateinit var list: ListView
    private var logins: List<Login> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val available = SyncCoordinator.get(this).runtimeOrNull()
        if (available == null || !runCatching { available.snapshot().vaultUnlocked }.getOrDefault(false)) {
            Toast.makeText(this, R.string.sync_vault_locked, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        runtime = available
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.sync_manage_passwords)

        val add = Button(this).apply {
            setText(R.string.sync_add_password)
            setOnClickListener { editLogin(null) }
        }
        list = ListView(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(add, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(
                list,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        })
        list.setOnItemClickListener { _, _, position, _ -> editLogin(logins[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(logins[position])
            true
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching(runtime::listLogins) }
                .getOrElse {
                    finishForUnavailableRuntime()
                    return@launch
                }
            if (!runtimeCallOrFalse { runtime.touchVault() }) return@launch
            logins = loaded.sortedWith(compareBy(Login::origin, Login::username))
            list.adapter = ArrayAdapter(
                this@PasswordManagerActivity,
                android.R.layout.simple_list_item_1,
                logins.map { "${it.origin} — ${it.username.ifBlank { getString(R.string.sync_empty_username) }}" },
            )
        }
    }

    override fun onStop() {
        // Do not retain decrypted Login objects while this screen is hidden.
        logins = emptyList()
        if (::list.isInitialized) list.adapter = null
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (!::runtime.isInitialized) return
        if (runCatching { runtime.snapshot().vaultUnlocked }.getOrDefault(false)) refresh()
        else finishForUnavailableRuntime()
    }

    private fun editLogin(existing: Login?) {
        if (!runtimeCallOrFalse { runtime.touchVault() }) return
        val origin = EditText(this).apply {
            hint = getString(R.string.sync_password_origin)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.origin.orEmpty())
        }
        val username = EditText(this).apply {
            hint = getString(R.string.sync_password_username)
            setText(existing?.username.orEmpty())
        }
        val password = EditText(this).apply {
            hint = getString(R.string.sync_password_value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(existing?.password.orEmpty())
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(origin)
            addView(username)
            addView(password)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.sync_add_password else R.string.sync_edit_password)
            .setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val canonicalOrigin = canonicalHttpsOrigin(origin.text.toString())
                val secret = password.text.toString()
                if (canonicalOrigin == null || secret.isEmpty()) {
                    Toast.makeText(this, R.string.sync_password_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val entry = LoginEntry(
                    origin = canonicalOrigin,
                    httpRealm = null,
                    formActionOrigin = canonicalOrigin,
                    usernameField = "",
                    passwordField = "",
                    password = secret,
                    username = username.text.toString(),
                )
                lifecycleScope.launch {
                    val changed = withContext(Dispatchers.IO) {
                        runCatching {
                            if (existing == null) runtime.addLogin(entry)
                            else runtime.updateLogin(existing.id, entry)
                        }
                    }
                    if (changed.isFailure) {
                        finishForUnavailableRuntime()
                        return@launch
                    }
                    SyncCoordinator.get(this@PasswordManagerActivity).recordLocalChange()
                    refresh()
                }
            }
            .show()
    }

    private fun confirmDelete(login: Login) {
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_delete_password)
            .setMessage(login.origin)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sync_delete_password) { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        runCatching { runtime.deleteLogin(login.id) }
                    }
                    if (deleted.isFailure) {
                        finishForUnavailableRuntime()
                        return@launch
                    }
                    SyncCoordinator.get(this@PasswordManagerActivity).recordLocalChange()
                    refresh()
                }
            }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun runtimeCallOrFalse(block: () -> Boolean): Boolean =
        runCatching(block).getOrDefault(false).also { available ->
            if (!available) finishForUnavailableRuntime()
        }

    private fun finishForUnavailableRuntime() {
        if (!isFinishing) {
            Toast.makeText(this, R.string.sync_vault_locked, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun canonicalHttpsOrigin(value: String): String? = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null)
        URI("https", null, uri.host, uri.port, null, null, null).toASCIIString()
    }.getOrNull()
}
