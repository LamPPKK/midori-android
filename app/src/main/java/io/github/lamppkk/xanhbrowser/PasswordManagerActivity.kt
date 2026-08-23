package io.github.lamppkk.xanhbrowser

import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.github.lamppkk.xanhbrowser.sync.CredentialPolicy
import io.github.lamppkk.xanhbrowser.sync.XanhSyncRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.appservices.logins.Login
import mozilla.appservices.logins.LoginEntry

/** Xanh-only password manager; it does not register as an OS autofill service. */
class PasswordManagerActivity : AppCompatActivity() {
    private lateinit var runtime: XanhSyncRuntime
    private lateinit var list: ListView
    private var logins: List<Login> = emptyList()
    private var activeDialog: AlertDialog? = null
    private var surfaceActive = false
    private var surfaceGeneration = 0L

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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            }
            addView(add, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(
                list,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(content)
        list.setOnItemClickListener { _, _, position, _ -> editLogin(logins[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(logins[position])
            true
        }
    }

    private fun refresh() {
        val generation = surfaceGeneration
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(runtime::listLogins) }
            if (!isSurfaceCurrent(generation)) return@launch
            val loaded = result.getOrElse {
                finishForUnavailableRuntime()
                return@launch
            }
            logins = loaded.sortedWith(compareBy(Login::origin, Login::username))
            list.adapter = ArrayAdapter(
                this@PasswordManagerActivity,
                android.R.layout.simple_list_item_1,
                logins.map {
                    "${it.origin} — ${displayUsername(it.username)}"
                },
            )
        }
    }

    override fun onStop() {
        // Do not retain decrypted Login objects while this screen is hidden.
        surfaceActive = false
        surfaceGeneration++
        activeDialog?.dismiss()
        activeDialog = null
        logins = emptyList()
        if (::list.isInitialized) list.adapter = null
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (!::runtime.isInitialized) return
        surfaceActive = true
        surfaceGeneration++
        if (runCatching { runtime.snapshot().vaultUnlocked }.getOrDefault(false)) refresh()
        else finishForUnavailableRuntime()
    }

    private fun editLogin(existing: Login?) {
        if (activeDialog != null || !runtimeCallOrFalse { runtime.touchVault() }) return
        if (existing != null && !CredentialPolicy.isSafeManagerCredential(existing)) {
            refresh()
            return
        }
        val generation = surfaceGeneration
        val origin = EditText(this).apply {
            hint = getString(R.string.sync_password_origin)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(CredentialPolicy.MAX_CREDENTIAL_ORIGIN_BYTES))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(existing?.origin.orEmpty())
        }
        val username = EditText(this).apply {
            hint = getString(R.string.sync_password_username)
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(CredentialPolicy.MAX_CREDENTIAL_USERNAME_BYTES))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(existing?.username.orEmpty())
        }
        val password = EditText(this).apply {
            hint = getString(R.string.sync_password_value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(CredentialPolicy.MAX_CREDENTIAL_PASSWORD_BYTES))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(existing?.password.orEmpty())
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            }
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(origin)
            addView(username)
            addView(password)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.sync_add_password else R.string.sync_edit_password)
            .setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        activeDialog = dialog
        dialog.setOnDismissListener {
            password.text?.clear()
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!isSurfaceCurrent(generation)) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val canonicalOrigin = CredentialPolicy.canonicalHttpsOrigin(
                    origin.text.toString().trim(),
                    requireOriginOnly = true,
                )
                val secret = password.text.toString()
                val entry = canonicalOrigin?.let {
                    LoginEntry(
                        origin = it,
                        httpRealm = null,
                        formActionOrigin = it,
                        usernameField = existing?.usernameField.orEmpty(),
                        passwordField = existing?.passwordField.orEmpty(),
                        password = secret,
                        username = username.text.toString(),
                    )
                }
                if (entry == null || !CredentialPolicy.isAllowedMutation(entry) ||
                    existing?.id?.let(CredentialPolicy::isValidCredentialId) == false
                ) {
                    Toast.makeText(this, R.string.sync_password_invalid, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                setEditorEnabled(origin, username, password, dialog, enabled = false)
                lifecycleScope.launch {
                    val changed = withContext(NonCancellable + Dispatchers.IO) {
                        val mutation = runCatching {
                            if (existing == null) runtime.addLogin(entry)
                            else runtime.updateLogin(existing.id, entry)
                        }
                        if (mutation.isSuccess) {
                            // Runtime scheduling is authoritative. This also
                            // wakes WorkManager, but its failure must not turn an
                            // already committed mutation into a retryable error.
                            runCatching {
                                SyncCoordinator.get(this@PasswordManagerActivity).recordLocalChange()
                            }
                        }
                        mutation
                    }
                    if (!isSurfaceCurrent(generation) || activeDialog !== dialog) return@launch
                    if (changed.isFailure) {
                        if (runCatching { runtime.snapshot().vaultUnlocked }.getOrDefault(false)) {
                            setEditorEnabled(origin, username, password, dialog, enabled = true)
                            Toast.makeText(
                                this@PasswordManagerActivity,
                                R.string.sync_password_operation_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            finishForUnavailableRuntime()
                        }
                        return@launch
                    }
                    password.text?.clear()
                    dialog.dismiss()
                    refresh()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(login: Login) {
        if (activeDialog != null || !CredentialPolicy.isSafeManagerCredential(login) ||
            !runtimeCallOrFalse { runtime.touchVault() }
        ) return
        val generation = surfaceGeneration
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.sync_delete_password)
            .setMessage(login.origin)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sync_delete_password, null)
            .create()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!isSurfaceCurrent(generation)) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                lifecycleScope.launch {
                    val deleted = withContext(NonCancellable + Dispatchers.IO) {
                        val mutation = runCatching { runtime.deleteLogin(login.id) }
                        if (mutation.getOrDefault(false)) {
                            // Deletion is committed before the WorkManager
                            // wake-up, so a scheduler error must not invite a
                            // misleading second confirmation.
                            runCatching {
                                SyncCoordinator.get(this@PasswordManagerActivity).recordLocalChange()
                            }
                        }
                        mutation
                    }
                    if (!isSurfaceCurrent(generation) || activeDialog !== dialog) return@launch
                    if (deleted.isFailure) {
                        if (runCatching { runtime.snapshot().vaultUnlocked }.getOrDefault(false)) {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            Toast.makeText(
                                this@PasswordManagerActivity,
                                R.string.sync_password_operation_failed,
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            finishForUnavailableRuntime()
                        }
                        return@launch
                    }
                    dialog.dismiss()
                    refresh()
                }
            }
        }
        dialog.show()
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
            activeDialog?.dismiss()
            activeDialog = null
            logins = emptyList()
            if (::list.isInitialized) list.adapter = null
            Toast.makeText(this, R.string.sync_vault_locked, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun isSurfaceCurrent(generation: Long): Boolean =
        surfaceActive && surfaceGeneration == generation &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !isFinishing

    private fun setEditorEnabled(
        origin: EditText,
        username: EditText,
        password: EditText,
        dialog: AlertDialog,
        enabled: Boolean,
    ) {
        origin.isEnabled = enabled
        username.isEnabled = enabled
        password.isEnabled = enabled
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = enabled
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = enabled
    }

    private fun displayUsername(value: String): String {
        val safe = buildString(value.length.coerceAtMost(MAX_DISPLAY_USERNAME_CHARS)) {
            var offset = 0
            while (offset < value.length) {
                val codePoint = value.codePointAt(offset)
                val characters = Character.charCount(codePoint)
                if (length + characters > MAX_DISPLAY_USERNAME_CHARS) break
                if (!Character.isISOControl(codePoint) &&
                    Character.getType(codePoint) != Character.FORMAT.toInt()
                ) {
                    appendCodePoint(codePoint)
                }
                offset += characters
            }
        }.trim().replace(DISPLAY_WHITESPACE, " ")
        return safe.ifBlank { getString(R.string.sync_empty_username) }
    }

    companion object {
        private const val MAX_DISPLAY_USERNAME_CHARS = 256
        private val DISPLAY_WHITESPACE = Regex("\\s+")
    }
}
