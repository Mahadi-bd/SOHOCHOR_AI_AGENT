package com.sahachor.app.docs

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/** Generated files live in app-private storage and leave the app only through an explicit Share/Open action. */
class DocumentStore(private val ctx: Context) {
    private val dir: File get() = File(ctx.filesDir, "documents").apply { mkdirs() }

    fun write(ext: String, base: String, producer: (OutputStream) -> Unit): File {
        var f = File(dir, "$base.$ext"); var n = 2
        while (f.exists()) { f = File(dir, "${base}_$n.$ext"); n++ }
        try { f.outputStream().use(producer) } catch (e: Exception) { f.delete(); throw e }
        return f
    }

    fun list(): List<File> = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun named(name: String) = File(dir, name.substringAfterLast('/')).takeIf { it.isFile }

    fun delete(name: String) { named(name)?.delete() }

    fun uriFor(name: String): Uri? = named(name)?.let { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }
}
