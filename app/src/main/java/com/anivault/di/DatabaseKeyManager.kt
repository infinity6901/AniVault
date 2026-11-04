package com.anivault.di

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps the SQLCipher passphrase with a non-exportable Android Keystore AES key. */
internal object DatabaseKeyManager {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "anivault.room.key.wrap.v1"
    const val PREFS = "anivault_secure_db"
    private const val WRAPPED = "wrapped_passphrase"

    /** Returns the database passphrase, creating and persisting it on first use. Throws if the stored key can't be unwrapped. */
    @Synchronized fun passphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(WRAPPED, null)
        if (existing != null) return unwrap(getKey(), Base64.decode(existing, Base64.NO_WRAP))
        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.iv + cipher.doFinal(raw)
        check(prefs.edit().putString(WRAPPED, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Could not persist the encrypted database key."
        }
        return raw
    }

    /**
     * Called when the wrapped passphrase can no longer be unwrapped (Keystore key lost, e.g. after
     * a device restore). The unreadable database is KEPT under a new name – never deleted – and a
     * fresh passphrase is generated on the next [passphrase] call.
     */
    @Synchronized fun quarantineUnreadable(context: Context, dbName: String) {
        val stamp = System.currentTimeMillis()
        val base = context.getDatabasePath(dbName)
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val f = File(base.path + suffix)
            if (f.exists()) f.renameTo(File(f.path + ".unreadable-" + stamp))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(WRAPPED).commit()
    }

    private fun unwrap(key: SecretKey, value: ByteArray): ByteArray {
        require(value.size > 12) { "Encrypted database key is corrupt." }
        val iv = value.copyOfRange(0, 12)
        val ciphertext = value.copyOfRange(12, value.size)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(ciphertext)
        }
    }

    private fun getKey(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
