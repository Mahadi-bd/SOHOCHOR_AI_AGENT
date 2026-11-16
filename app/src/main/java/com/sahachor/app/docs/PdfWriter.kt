package com.sahachor.app.docs

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/**
 * A4 PDF built with Android's PdfDocument (system fonts handle Bengali shaping).
 * Design: coloured title band, coloured headings with an accent bar, coloured bullets, zebra tables with a repeating header row, page footer with numbers.
 */
object PdfWriter {
    private const val W = 595
    private const val H = 842
    private const val M = 48

    fun write(spec: DocSpec, out: OutputStream) {
        val pal = Palette.of(spec.theme)
        val primary = Color.parseColor("#" + pal.primary)
        val light = Color.parseColor("#" + pal.light)
        val ink = Color.parseColor("#1F2937")
        val gray = Color.parseColor("#6B7280")
        val rule = Color.parseColor("#E5E7EB")
        val contentW = W - 2 * M

        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var y = M.toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        fun footer() {
            val c = page.canvas
            fill.color = rule
            c.drawRect(M.toFloat(), (H - 38).toFloat(), (W - M).toFloat(), (H - 37).toFloat(), fill)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = gray }
            c.drawText("সহচর", M.toFloat(), (H - 22).toFloat(), tp)
            val label = pageNo.toString()
            c.drawText(label, W - M - tp.measureText(label), (H - 22).toFloat(), tp)
        }

        fun newPage() {
            footer()
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M.toFloat()
        }

        fun layoutOf(text: String, size: Float, bold: Boolean, color: Int, width: Int, mult: Float): StaticLayout {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, tp, width).setLineSpacing(0f, mult).build()
        }

        /** Draws text line by line so a long paragraph flows across pages. */
        fun draw(text: String, size: Float, bold: Boolean, color: Int, indent: Int, before: Float, after: Float, dot: Boolean = false) {
            if (text.isBlank()) return
            val width = contentW - indent
            val layout = layoutOf(text, size, bold, color, width, 1.3f)
            y += before
            for (i in 0 until layout.lineCount) {
                val top = layout.getLineTop(i)
                val bottom = layout.getLineBottom(i)
                if (y + (bottom - top) > H - M - 14) newPage()
                val c = page.canvas
                if (dot && i == 0) {
                    fill.color = primary
                    c.drawCircle(M + 4f, y + (bottom - top) / 2f, 2.8f, fill)
                }
                c.save()
                c.translate((M + indent).toFloat(), y - top)
                c.clipRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
                layout.draw(c)
                c.restore()
                y += (bottom - top)
            }
            y += after
        }

        fun heading(text: String) {
            val layout = layoutOf(text, 16f, true, primary, contentW - 12, 1.2f)
            val h = layout.height.toFloat()
            if (y + 14 + h + 40 > H - M) newPage()
            y += 14
            val c = page.canvas
            fill.color = primary
            c.drawRect(M.toFloat(), y, M + 4f, y + h, fill)
            c.save(); c.translate(M + 12f, y); layout.draw(c); c.restore()
            y += h + 6
        }

        fun table(rows: List<List<String>>) {
            val n = rows.maxOf { it.size }.coerceAtLeast(1)
            val cw = contentW / n
            val pad = 6
            fun drawRow(r: List<String>, head: Boolean, zebra: Boolean, repeatHeader: Boolean) {
                val ls = (0 until n).map { j -> layoutOf(r.getOrElse(j) { "" }, 10.5f, head, if (head) Color.WHITE else ink, cw - 2 * pad, 1.2f) }
                val h = (ls.maxOf { it.height } + 2 * pad).toFloat()
                if (y + h > H - M - 14) {
                    newPage()
                    if (!head && repeatHeader) drawRow(rows[0], true, false, false)
                }
                val c = page.canvas
                fill.color = if (head) primary else if (zebra) light else Color.WHITE
                c.drawRect(M.toFloat(), y, (M + contentW).toFloat(), y + h, fill)
                fill.color = Color.parseColor("#D1D5DB")
                c.drawRect(M.toFloat(), y + h - 1f, (M + contentW).toFloat(), y + h, fill)
                ls.forEachIndexed { j, l ->
                    c.save(); c.translate((M + j * cw + pad).toFloat(), y + pad); l.draw(c); c.restore()
                }
                y += h
            }
            rows.forEachIndexed { i, r -> drawRow(r, i == 0, i % 2 == 0 && i > 0, true) }
            y += 10
        }

        // Title band on the first page
        val titleLayout = layoutOf(spec.title, 24f, true, Color.WHITE, contentW, 1.2f)
        val bandH = maxOf(96f, titleLayout.height + 56f)
        fill.color = primary
        page.canvas.drawRect(0f, 0f, W.toFloat(), bandH, fill)
        page.canvas.save(); page.canvas.translate(M.toFloat(), (bandH - titleLayout.height) / 2f); titleLayout.draw(page.canvas); page.canvas.restore()
        y = bandH + 22f

        for (b in spec.blocks) when (b) {
            is Block.Heading -> heading(b.text)
            is Block.Para -> draw(b.text, 11.5f, false, ink, 0, 0f, 6f)
            is Block.Bullets -> b.items.forEach { draw(it, 11.5f, false, ink, 16, 0f, 4f, dot = true) }
            is Block.Table -> table(b.rows)
        }
        footer()
        doc.finishPage(page)
        try { doc.writeTo(out) } finally { doc.close() }
    }
}
