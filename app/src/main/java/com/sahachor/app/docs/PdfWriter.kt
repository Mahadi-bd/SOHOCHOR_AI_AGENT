package com.sahachor.app.docs

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/** A4 PDF using Android's built-in PdfDocument; system fonts handle Bengali shaping. Text flows line by line across pages. */
object PdfWriter {
    private const val W = 595
    private const val H = 842
    private const val M = 48

    fun write(spec: DocSpec, out: OutputStream) {
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var y = M.toFloat()
        val contentW = W - 2 * M

        fun newPage() {
            doc.finishPage(page); pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M.toFloat()
        }

        fun draw(text: String, size: Float, bold: Boolean, indent: Int, before: Float, after: Float) {
            if (text.isBlank()) return
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; color = Color.BLACK
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            val width = contentW - indent
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, width).setLineSpacing(0f, 1.15f).build()
            y += before
            for (i in 0 until layout.lineCount) {
                val top = layout.getLineTop(i); val bottom = layout.getLineBottom(i)
                if (y + (bottom - top) > H - M) newPage()
                val c = page.canvas
                c.save()
                c.translate((M + indent).toFloat(), y - top)
                c.clipRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
                layout.draw(c)
                c.restore()
                y += (bottom - top)
            }
            y += after
        }

        draw(spec.title, 22f, true, 0, 0f, 10f)
        for (b in spec.blocks) when (b) {
            is Block.Heading -> draw(b.text, 15f, true, 0, 10f, 4f)
            is Block.Para -> draw(b.text, 11f, false, 0, 0f, 6f)
            is Block.Bullets -> b.items.forEach { draw("• $it", 11f, false, 14, 0f, 3f) }
        }
        doc.finishPage(page)
        try { doc.writeTo(out) } finally { doc.close() }
    }
}
