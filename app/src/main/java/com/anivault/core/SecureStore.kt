package com.anivault.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small encrypted key/value store for secrets (OAuth tokens, client ids). Values are sealed with
 * AES-256-GCM using a non-exportable Android Keystore key. A value that can no longer be
 * decrypted (e.g. after a device restore) reads as null – callers treat that as "not set".
 */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("anivault_secrets", Context.MODE_PRIVATE)

    @Synchronized
    fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching { open(Base64.decode(raw, Base64.NO_WRAP)) }.getOrNull()
    }

    @Synchronized
    fun put(key: String, value: String) {
        val sealed = seal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) {
            "Could not store the secret."
        }
    }

    @Synchronized
    fun remove(key: String) { prefs.edit().remove(key).apply() }

    @Synchronized
    fun removeWithPrefix(prefix: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** Every readable secret whose key starts with one of [prefixes]. */
    @Synchronized
    fun snapshot(prefixes: List<String>): Map<String, String> =
        prefs.all.keys.filter { k -> prefixes.any { k.startsWith(it) } }
            .mapNotNull { k -> get(k)?.let { k to it } }.toMap()

    @Synchronized
    fun restore(values: Map<String, String>, prefixes: List<String>) {
        values.filterKeys { k -> prefixes.any { k.startsWith(it) } }.forEach { (k, v) -> put(k, v) }
    }

    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun open(sealed: ByteArray): String {
        require(sealed.size > IV_SIZE) { "corrupt" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, IV_SIZE)))
        return String(cipher.doFinal(sealed.copyOfRange(IV_SIZE, sealed.size)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "anivault.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
