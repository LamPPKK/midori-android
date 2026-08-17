package io.github.lamppkk.xanhbrowser.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps the Application Services logins key with OS authentication.
 *
 * The host must call [unwrap] only after BiometricPrompt/device credential
 * succeeds. Android invalidates access after five minutes; the runtime also
 * drops the plaintext key immediately on background.
 */
class VaultKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hasWrappedKey(): Boolean = preferences.contains(WRAPPED_KEY)

    fun wrap(plaintextKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintextKey.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        preferences.edit().putString(WRAPPED_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun unwrap(): String {
        val encoded = requireNotNull(preferences.getString(WRAPPED_KEY, null))
        val packed = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        val ivLength = packed.get().toInt() and 0xff
        require(ivLength in 12..16)
        val iv = ByteArray(ivLength).also(packed::get)
        val ciphertext = ByteArray(packed.remaining()).also(packed::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    fun delete() {
        preferences.edit().remove(WRAPPED_KEY).apply()
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            deleteEntry(KEY_ALIAS)
        }
    }

    @Suppress("DEPRECATION")
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(VAULT_TIMEOUT_SECONDS)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val VAULT_TIMEOUT_SECONDS = 5 * 60
        private const val PREFERENCES = "xanh_sync_vault"
        private const val WRAPPED_KEY = "wrapped_logins_key"
        private const val KEY_ALIAS = "xanh_sync_logins_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
