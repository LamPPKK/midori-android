package io.github.lamppkk.xanhbrowser

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.github.lamppkk.xanhbrowser.databinding.ActivitySyncSettingsBinding
import io.github.lamppkk.xanhbrowser.sync.AccountServer
import io.github.lamppkk.xanhbrowser.sync.AccountState
import io.github.lamppkk.xanhbrowser.sync.SyncConfiguration
import io.github.lamppkk.xanhbrowser.sync.SyncReason
import io.github.lamppkk.xanhbrowser.sync.SyncEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySyncSettingsBinding
    private val coordinator by lazy { SyncCoordinator.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.useSelfHosted.setOnCheckedChangeListener { _, checked -> showSelfHosted(checked) }
        if (BuildConfig.XANH_SYNC_SELF_HOSTED_ONLY) {
            binding.useSelfHosted.isChecked = true
            binding.useSelfHosted.isEnabled = false
        }
        binding.signIn.setOnClickListener { signIn() }
        binding.syncNow.setOnClickListener { syncNow() }
        binding.unlockPasswords.setOnClickListener { unlockVault() }
        binding.managePasswords.setOnClickListener {
            startActivity(Intent(this, PasswordManagerActivity::class.java))
        }
        binding.disconnect.setOnClickListener { chooseDisconnect() }
        binding.syncBookmarks.setOnCheckedChangeListener { _, enabled ->
            coordinator.setEngineEnabled(SyncEngine.BOOKMARKS, enabled)
        }
        binding.syncHistory.setOnCheckedChangeListener { _, enabled ->
            coordinator.setEngineEnabled(SyncEngine.HISTORY, enabled)
        }
        binding.syncTabs.setOnCheckedChangeListener { _, enabled ->
            coordinator.setEngineEnabled(SyncEngine.TABS, enabled)
        }
        binding.syncPasswords.setOnCheckedChangeListener { _, enabled ->
            coordinator.setEngineEnabled(SyncEngine.PASSWORDS, enabled)
        }
        showSelfHosted(binding.useSelfHosted.isChecked)
        render()
        if (intent.getBooleanExtra(EXTRA_AUTH_SUCCESS, false)) {
            Toast.makeText(this, R.string.sync_connected, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                runCatching { coordinator.sync(SyncReason.STARTUP) }
                render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun showSelfHosted(show: Boolean) {
        binding.accountsUrlLayout.visibility = if (show) View.VISIBLE else View.GONE
        binding.tokenUrlLayout.visibility = if (show) View.VISIBLE else View.GONE
        binding.clientIdLayout.visibility = if (show || BuildConfig.XANH_FXA_CLIENT_ID.isBlank()) View.VISIBLE else View.GONE
    }

    private fun signIn() {
        runCatching {
            val clientId = binding.clientId.text?.toString()?.trim().orEmpty()
                .ifBlank { BuildConfig.XANH_FXA_CLIENT_ID }
            val server = if (binding.useSelfHosted.isChecked) {
                AccountServer.SelfHosted(
                    binding.accountsUrl.text?.toString()?.trim().orEmpty(),
                    binding.tokenUrl.text?.toString()?.trim().orEmpty(),
                )
            } else {
                AccountServer.Mozilla
            }
            val configuration = SyncConfiguration(
                server,
                clientId,
                SyncCoordinator.REDIRECT_URI,
                getString(R.string.app_name),
            )
            configuration.validate()
            val domain = configuration.accountDomain()
            AlertDialog.Builder(this)
                .setTitle(R.string.sync_sign_in)
                .setMessage(getString(R.string.sync_domain_confirmation, domain))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.continue_label) { _, _ ->
                    lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                coordinator.configure(configuration)
                                coordinator.beginOAuth()
                            }
                        }.onSuccess { oauthUrl ->
                            CustomTabsIntent.Builder().build().launchUrl(
                                this@SyncSettingsActivity,
                                oauthUrl.toUri(),
                            )
                        }.onFailure {
                            Toast.makeText(
                                this@SyncSettingsActivity,
                                it.message ?: getString(R.string.sync_configuration_invalid),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                .show()
        }.onFailure { Toast.makeText(this, it.message ?: getString(R.string.sync_configuration_invalid), Toast.LENGTH_LONG).show() }
    }

    private fun syncNow() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching { coordinator.sync(SyncReason.MANUAL) }
                .onSuccess { Toast.makeText(this@SyncSettingsActivity, getString(R.string.sync_complete, it.status.name), Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this@SyncSettingsActivity, it.message, Toast.LENGTH_LONG).show() }
            binding.progress.visibility = View.GONE
            render()
        }
    }

    private fun unlockVault() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { coordinator.runtimeOrNull()?.unlockVault() }
                        }.onFailure {
                            Toast.makeText(this@SyncSettingsActivity, it.message, Toast.LENGTH_LONG).show()
                        }
                        render()
                    }
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.sync_unlock_passwords))
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    private fun chooseDisconnect() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_disconnect)
            .setSingleChoiceItems(
                arrayOf(getString(R.string.sync_keep_local), getString(R.string.sync_delete_local)),
                0,
                null,
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sync_disconnect) { dialog, _ ->
                val delete = (dialog as AlertDialog).listView.checkedItemPosition == 1
                lifecycleScope.launch {
                    coordinator.disconnect(delete)
                    render()
                }
            }
            .show()
    }

    private fun render() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { coordinator.snapshot() }
            binding.status.text = when (snapshot?.accountState) {
                AccountState.CONNECTED -> getString(R.string.sync_status_connected)
                AccountState.AUTHENTICATING -> getString(R.string.sync_status_authenticating)
                AccountState.AUTH_ISSUES -> getString(R.string.sync_status_auth_issues)
                else -> getString(R.string.sync_status_disconnected)
            }
            val connected = snapshot?.accountState == AccountState.CONNECTED
            binding.syncBookmarks.isChecked = snapshot?.enabledEngines?.contains(SyncEngine.BOOKMARKS) ?: true
            binding.syncHistory.isChecked = snapshot?.enabledEngines?.contains(SyncEngine.HISTORY) ?: true
            binding.syncTabs.isChecked = snapshot?.enabledEngines?.contains(SyncEngine.TABS) ?: true
            binding.syncPasswords.isChecked = snapshot?.enabledEngines?.contains(SyncEngine.PASSWORDS) ?: true
            binding.syncNow.isEnabled = connected
            binding.unlockPasswords.isEnabled = connected
            binding.managePasswords.isEnabled = connected && snapshot?.vaultUnlocked == true
            binding.disconnect.isEnabled = snapshot != null && snapshot.accountState != AccountState.DISCONNECTED
            binding.vaultStatus.text = if (snapshot?.vaultUnlocked == true) {
                getString(R.string.sync_vault_unlocked)
            } else {
                getString(R.string.sync_vault_locked)
            }
        }
    }

    companion object {
        const val EXTRA_AUTH_SUCCESS = "auth_success"
    }
}
