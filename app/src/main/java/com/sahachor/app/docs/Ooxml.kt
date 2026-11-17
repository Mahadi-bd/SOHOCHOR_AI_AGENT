package com.sahachor.app.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
private const val OD = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val RELS_CT = "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"

internal fun xmlEscape(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (ch in s) {
        when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> {} // illegal in XML 1.0
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

internal fun ZipOutputStream.put(name: String, content: String) {
    putNextEntry(ZipEntry(name)); write(content.toByteArray(Charsets.UTF_8)); closeEntry()
}

internal fun ZipOutputStream.putBytes(name: String, data: ByteArray) {
    putNextEntry(ZipEntry(name)); write(data); closeEntry()
}

internal const val PNG_DEFAULT = "<Default Extension=\"png\" ContentType=\"image/png\"/>"

internal fun relationships(vararg items: Triple<String, String, String>): String =
    XML + "<Relationships xmlns=\"$REL_NS\">" + items.joinToString("") { (id, type, target) -> "<Relationship Id=\"$id\" Type=\"$OD/$type\" Target=\"$target\"/>" } + "</Relationships>"

internal fun contentTypes(vararg overrides: Pair<String, String>, extraDefaults: String = ""): String =
    XML + "<Types xmlns=\"$CT_NS\">" + RELS_CT + extraDefaults + overrides.joinToString("") { (part, type) -> "<Override PartName=\"$part\" ContentType=\"$type\"/>" } + "</Types>"

/** Minimal, dependency-free Word writer (no Apache POI, which is too heavy for Android). */
object DocxWriter {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"

    fun build(spec: DocSpec, logo: Logo? = null): ByteArray {
        val pal = Palette.of(spec.theme)
        val useLogo = if (spec.branded) logo else null
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.put("[Content_Types].xml", contentTypes(
                "/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                "/word/styles.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml",
                extraDefaults = if (useLogo != null) PNG_DEFAULT else "",
            ))
            z.put("_rels/.rels", relationships(Triple("rId1", "officeDocument", "word/document.xml")))
            val rels = mutableListOf(Triple("rId1", "styles", "styles.xml"))
            if (useLogo != null) rels += Triple("rId2", "image", "media/logo.png")
            z.put("word/_rels/document.xml.rels", relationships(*rels.toTypedArray()))
            z.put("word/styles.xml", styles(pal))
            z.put("word/document.xml", document(spec, pal, useLogo))
            if (useLogo != null) z.putBytes("word/media/logo.png", useLogo.png)
        }
        return bos.toByteArray()
    }

    /** Logo (when available) followed by the LIFE EASY name, at the top of the first page. */
    private fun brandParagraph(pal: Pal, logo: Logo?): String {
        val drawing = if (logo != null) {
            val h = 457200L
            val w = h * logo.width / logo.height
            """<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$w" cy="$h"/><wp:docPr id="1" name="LIFE EASY logo" descr="LIFE EASY"/><a:graphic xmlns:a="$A"><a:graphicData uri="$PIC"><pic:pic xmlns:pic="$PIC"><pic:nvPicPr><pic:cNvPr id="0" name="LIFE EASY logo"/><pic:cNvPicPr/><pic:nvPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$w" cy="$h"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"""
        } else ""
        return """<w:p><w:pPr><w:spacing w:after="120"/></w:pPr>$drawing<w:r><w:rPr><w:b/><w:bCs/><w:color w:val="${pal.primary}"/><w:spacing w:val="40"/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr><w:t xml:space="preserve">${xmlEscape("LIFE EASY")}</w:t></w:r></w:p>"""
    }

    private fun run(text: String, rpr: String = ""): String {
        val props = if (rpr.isEmpty()) "" else "<w:rPr>$rpr</w:rPr>"
        return "<w:r>$props<w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r>"
    }

    private fun para(text: String, style: String? = null): String {
        val st = if (style != null) "<w:pStyle w:val=\"$style\"/>" else ""
        return "<w:p><w:pPr>$st</w:pPr>${run(text)}</w:p>"
    }

    private fun bullet(text: String, pal: Pal): String =
        "<w:p><w:pPr><w:ind w:left=\"567\" w:hanging=\"283\"/></w:pPr>" +
            run("• $text", "<w:b/><w:bCs/><w:color w:val=\"${pal.primary}\"/>") + "</w:p>"

    private fun cell(text: String, width: Int, head: Boolean, zebra: Boolean, pal: Pal): String {
        val fill = if (head) pal.primary else if (zebra) pal.light else null
        val shd = if (fill != null) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$fill\"/>" else ""
        val rpr = if (head) "<w:b/><w:bCs/><w:color w:val=\"FFFFFF\"/>" else ""
        return "<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>$shd</w:tcPr><w:p><w:pPr><w:spacing w:before=\"40\" w:after=\"40\"/></w:pPr>${run(text, rpr)}</w:p></w:tc>"
    }

    private fun table(rows: List<List<String>>, pal: Pal): String {
        val n = rows.maxOf { it.size }.coerceAtLeast(1)
        val w = 9026 / n
        val grid = (0 until n).joinToString("") { "<w:gridCol w:w=\"$w\"/>" }
        fun b(side: String) = "<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/>"
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"${w * n}\" w:type=\"dxa\"/><w:tblBorders>${b("top")}${b("left")}${b("bottom")}${b("right")}${b("insideH")}${b("insideV")}</w:tblBorders><w:tblLook w:val=\"04A0\"/></w:tblPr><w:tblGrid>$grid</w:tblGrid>")
        rows.forEachIndexed { i, r ->
            val cells = (0 until n).joinToString("") { j -> cell(r.getOrElse(j) { "" }, w, i == 0, i % 2 == 0 && i > 0, pal) }
            val trpr = if (i == 0) "<w:trPr><w:cantSplit/><w:tblHeader/></w:trPr>" else "<w:trPr><w:cantSplit/></w:trPr>"
            sb.append("<w:tr>$trpr$cells</w:tr>")
        }
        return sb.append("</w:tbl><w:p><w:pPr><w:spacing w:after=\"60\"/></w:pPr></w:p>").toString() // Word needs a paragraph after a table
    }

    private fun document(spec: DocSpec, pal: Pal, logo: Logo?): String {
        val body = StringBuilder()
        if (spec.branded) body.append(brandParagraph(pal, logo))
        body.append(para(spec.title, "Title"))
        for (b in spec.blocks) when (b) {
            is Block.Heading -> body.append(para(b.text, "Heading1"))
            is Block.Para -> body.append(para(b.text))
            is Block.Bullets -> b.items.forEach { body.append(bullet(it, pal)) }
            is Block.Table -> body.append(table(b.rows, pal))
        }
        return XML + "<w:document xmlns:w=\"$W\" xmlns:wp=\"$WP\" xmlns:r=\"$OD\"><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1440\" w:bottom=\"1134\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
    }

    private fun styles(pal: Pal): String =
        XML + "<w:styles xmlns:w=\"$W\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Nirmala UI\"/><w:color w:val=\"1F2937\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults><w:styles><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style><w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:before=\"240\" w:after=\"160\"/><w:jc w:val=\"left\"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val=\"${pal.primary}\"/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr></w:style><w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:before=\"260\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val=\"${pal.primary}\"/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/></w:rPr></w:style></w:styles></w:styles>"
}

object XlsxWriter {
    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    fun colName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do { sb.append('A' + n % 26); n = n / 26 - 1 } while (n >= 0)
        return sb.reverse().toString()
    }

    fun sheetNames(raw: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return raw.mapIndexed { i, r ->
            val base = r.replace(Regex("[\\\\/?*\\[\\]:]"), "_").trim().take(28).ifEmpty { "Sheet${i + 1}" }
            var name = base; var k = 2
            while (!used.add(name.lowercase())) { name = "$base$k"; k++ }
            name
        }
    }

    fun build(spec: SheetSpec): ByteArray {
        val pal = Palette.of(spec.theme)
        val names = sheetNames(spec.sheets.map { it.name })
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            val overrides = mutableListOf(
                "/xl/workbook.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
                "/xl/styles.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml",
            )
            spec.sheets.indices.forEach { overrides += "/xl/worksheets/sheet${it + 1}.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml" }
            z.put("[Content_Types].xml", contentTypes(*overrides.toTypedArray()))
            z.put("_rels/.rels", relationships(Triple("rId1", "officeDocument", "xl/workbook.xml")))
            z.put("xl/workbook.xml", XML + "<workbook xmlns=\"$S\" xmlns:r=\"$OD\"><sheets>" + names.indices.joinToString("") { "<sheet name=\"${xmlEscape(names[it])}\" sheetId=\"${it + 1}\" r:id=\"rId${it + 1}\"/>" } + "</sheets></workbook>")
            val rels = names.indices.map { Triple("rId${it + 1}", "worksheet", "worksheets/sheet${it + 1}.xml") } + Triple("rId${names.size + 1}", "styles", "styles.xml")
            z.put("xl/_rels/workbook.xml.rels", relationships(*rels.toTypedArray()))
            z.put("xl/styles.xml", styles(pal))
            spec.sheets.forEachIndexed { i, sh -> z.put("xl/worksheets/sheet${i + 1}.xml", sheet(sh)) }
        }
        return bos.toByteArray()
    }

    private fun styles(pal: Pal): String =
        XML + "<styleSheet xmlns=\"$S\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF${pal.primary}\"/></patternFill></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"

    private fun sheet(sh: Sheet): String {
        val widths = mutableListOf<Int>()
        for (row in sh.rows) row.forEachIndexed { j, v ->
            val l = minOf(50, maxOf(8, (v.length * 1.2).toInt() + 2))
            if (j >= widths.size) widths += l else widths[j] = maxOf(widths[j], l)
        }
        val cols = widths.mapIndexed { j, w -> "<col min=\"${j + 1}\" max=\"${j + 1}\" width=\"$w\" customWidth=\"1\"/>" }.joinToString("")
        val sb = StringBuilder(XML + "<worksheet xmlns=\"$S\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><cols>$cols</cols><sheetData>")
        sh.rows.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, v ->
                val ref = colName(c) + (r + 1)
                val s = if (r == 0) 1 else if (r % 2 == 0) 2 else 0
                when {
                    v.isEmpty() -> sb.append("<c r=\"$ref\" s=\"$s\"/>")
                    NUMBER.matches(v) -> sb.append("<c r=\"$ref\" s=\"$s\"><v>$v</v></c>")
                    v.startsWith("=") && v.length > 1 && SafeFormula.isSafe(v.substring(1)) -> sb.append("<c r=\"$ref\" s=\"$s\"><f>${xmlEscape(v.substring(1))}</f></c>")
                    else -> sb.append("<c r=\"$ref\" s=\"$s\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(v)}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        return sb.append("</sheetData></worksheet>").toString()
    }
}
