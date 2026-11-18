package com.sahachor.app.docs

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.sahachor.app.R
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Generated files live in app-private storage and leave the app only through an explicit Share/Open action. */
class DocumentStore(private val ctx: Context) {
    private val dir: File get() = File(ctx.filesDir, "documents").apply { mkdirs() }

    fun write(ext: String, base: String, producer: (OutputStream) -> Unit): File {
        var f = File(dir, "$base.$ext"); var n = 2
        while (f.exists()) { f = File(dir, "${base}_$n.$ext"); n++ }
        try { f.outputStream().use(producer) } catch (e: Exception) { f.delete(); throw e }
        return f
    }

    /** The LIFE EASY logo shipped inside the app; null if unreadable (documents then carry the name only). */
    private val logoCache: Logo? by lazy {
        try { ctx.resources.openRawResource(R.drawable.life_easy_logo).use { Logo.parse(it.readBytes()) } } catch (e: Exception) { null }
    }
    fun logo(): Logo? = logoCache

    /** Bundles files (or all of them) into one ZIP. Returns null when there is nothing to bundle or the total is too large. */
    fun zip(base: String, names: List<String>?): File? {
        val files = (names?.mapNotNull { named(it) } ?: list()).filter { !it.name.endsWith(".zip", ignoreCase = true) }.distinct()
        if (files.isEmpty() || files.sumOf { it.length() } > MAX_ZIP_BYTES) return null
        return write("zip", base) { out ->
            ZipOutputStream(out).use { z ->
                files.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            }
        }
    }

    fun list(): List<File> = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private companion object { const val MAX_ZIP_BYTES = 50L * 1024 * 1024 }

    private fun named(name: String) = File(dir, name.substringAfterLast('/')).takeIf { it.isFile }

    fun delete(name: String) { named(name)?.delete() }

    fun fileOf(name: String): File? = named(name)

    fun uriFor(name: String): Uri? = named(name)?.let { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }
}
