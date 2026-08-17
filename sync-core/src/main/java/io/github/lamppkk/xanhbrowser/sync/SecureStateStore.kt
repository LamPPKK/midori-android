package io.github.lamppkk.xanhbrowser.sync

import android.annotation.SuppressLint
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

/** Device-bound encrypted persistence for FxA state and sync metadata.
 *
 * This key deliberately does not require foreground user authentication:
 * WorkManager must refresh non-password engines in the background. The
 * password database key is kept by [VaultKeyStore] under an authenticated key.
 */
class SecureStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun put(name: String, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(1 + cipher.iv.size + ciphertext.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        preferences.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val encoded = preferences.getString(name, null) ?: return null
        return runCatching {
            val packed = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
            val ivLength = packed.get().toInt() and 0xff
            require(ivLength in 12..16)
            val iv = ByteArray(ivLength).also(packed::get)
            val ciphertext = ByteArray(packed.remaining()).also(packed::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(vararg names: String) {
        preferences.edit().also { editor -> names.forEach(editor::remove) }.apply()
    }

    /** Removes encrypted state and its device-bound wrapping key. */
    @SuppressLint("ApplySharedPref") // Persist deletion before destroying the decrypting key.
    fun clear() {
        preferences.edit().clear().commit()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

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
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFERENCES = "xanh_sync_secure_state"
        private const val KEY_ALIAS = "xanh_sync_account_state_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
