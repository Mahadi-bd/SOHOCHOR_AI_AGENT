package com.sahachor.app.vault

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a fresh random 12-byte IV in front of every blob. Any change to the stored bytes makes decryption fail. */
class VaultCrypto(private val key: SecretKey) {
    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val body = c.doFinal(plain)
        return c.iv + body
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN + TAG_BYTES) { "blob too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, blob, 0, IV_LEN))
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    private companion object {
        const val IV_LEN = 12
        const val TAG_BYTES = 16
    }
}

data class VaultItem(val id: String, val name: String, val mime: String, val size: Long, val addedAt: Long)

object VaultIndex {
    fun toJson(items: List<VaultItem>): String {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("m", it.mime).put("s", it.size).put("t", it.addedAt)) }
        return a.toString()
    }

    fun fromJson(s: String): List<VaultItem> = try {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("i")
            if (id.isBlank()) null else VaultItem(id, o.optString("n"), o.optString("m"), o.optLong("s"), o.optLong("t"))
        }
    } catch (e: Exception) { emptyList() }
}

object VaultNames {
    /** A file name that is safe to use on disk: letters from any script, digits and ._- only. */
    fun safe(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{M}\\p{N}._ -]"), "_").trim().trimStart('.').take(80).ifEmpty { "file" }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** Reads a stream fully, or returns null if it is larger than max bytes (the vault keeps files in memory while encrypting). */
fun readLimited(input: InputStream, max: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
