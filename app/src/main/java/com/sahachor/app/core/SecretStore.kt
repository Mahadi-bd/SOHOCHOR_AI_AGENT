package com.sahachor.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (API keys, DB passphrase) with a hardware-backed Android Keystore key. Never throws. */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sahachor_secrets", Context.MODE_PRIVATE)

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String): Boolean = try {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, masterKey())
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = b64(c.iv) + ":" + b64(ct)
        prefs.edit().putString(name, packed).commit()
    } catch (e: Exception) { false }

    fun get(name: String): String? = try {
        val packed = prefs.getString(name, null) ?: return null
        val (iv, ct) = packed.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) { null }

    fun remove(name: String) { prefs.edit().remove(name).apply() }

    /** Random 256-bit database passphrase, created once. Returns null only if the Keystore is unusable. */
    fun databasePassphrase(): ByteArray? {
        get(DB_KEY)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        if (prefs.contains(DB_KEY)) return null // exists but undecryptable: do NOT silently replace it
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return if (put(DB_KEY, b64(raw))) raw else null
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    companion object {
        private const val ALIAS = "sahachor_master_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val DB_KEY = "db_passphrase"
    }
}
