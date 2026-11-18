package com.sahachor.app.vault

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.FileProvider
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The vault key lives in the Android Keystore (hardware-backed where available) and never leaves it. */
object VaultKeys {
    private const val ALIAS = "sahachor_vault_v1"

    fun keystoreKey(): SecretKey {
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
}

/**
 * Encrypted file vault. Files and the list of names are both encrypted; nothing here is ever sent to an AI or the network.
 * The unlock screen (fingerprint or screen lock) controls who can see the list; encryption protects the files at rest.
 */
class VaultStore(private val ctx: Context, private val crypto: VaultCrypto) {
    private val dir: File get() = File(ctx.filesDir, "vault").apply { mkdirs() }
    private val indexFile: File get() = File(dir, "index.bin")
    private val tmpDir: File get() = File(ctx.cacheDir, "vault_tmp").apply { mkdirs() }

    @Synchronized
    fun list(): List<VaultItem> = try { readIndexStrict().sortedByDescending { it.addedAt } } catch (e: Exception) { emptyList() }

    @Synchronized
    fun add(name: String, mime: String, data: ByteArray): VaultItem {
        require(data.size <= MAX_BYTES) { "file too large" }
        val current = readIndexStrict() // throws instead of overwriting an index we cannot read
        val id = UUID.randomUUID().toString()
        File(dir, "$id.bin").writeBytes(crypto.encrypt(data))
        val item = VaultItem(id, name.take(120), mime, data.size.toLong(), System.currentTimeMillis())
        writeIndex(current + item)
        return item
    }

    @Synchronized
    fun readBytes(id: String): ByteArray? = try {
        if (!isId(id)) null else File(dir, "$id.bin").takeIf { it.isFile }?.let { crypto.decrypt(it.readBytes()) }
    } catch (e: Exception) { null }

    @Synchronized
    fun delete(id: String) {
        if (!isId(id)) return
        val current = readIndexStrict()
        File(dir, "$id.bin").delete()
        writeIndex(current.filterNot { it.id == id })
    }

    /** Decrypts to a temporary file so another app can open it. Call wipeTemp() when the vault locks. */
    @Synchronized
    fun exportTemp(id: String): File? {
        val item = list().firstOrNull { it.id == id } ?: return null
        val bytes = readBytes(id) ?: return null
        val f = File(tmpDir, VaultNames.safe(item.name))
        f.writeBytes(bytes)
        return f
    }

    fun wipeTemp() { tmpDir.listFiles()?.forEach { it.delete() } }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)

    private fun readIndexStrict(): List<VaultItem> =
        if (indexFile.isFile) VaultIndex.fromJson(String(crypto.decrypt(indexFile.readBytes()), Charsets.UTF_8)) else emptyList()

    private fun writeIndex(items: List<VaultItem>) {
        val tmp = File(dir, "index.tmp")
        tmp.writeBytes(crypto.encrypt(VaultIndex.toJson(items).toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
    }

    private fun isId(s: String) = s.length == 36 && s.all { it.isLetterOrDigit() || it == '-' }

    companion object { const val MAX_BYTES = 25 * 1024 * 1024 }
}
