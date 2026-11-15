package com.sahachor.app.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
private const val CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
private const val OD = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

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

private fun ZipOutputStream.put(name: String, content: String) {
    putNextEntry(ZipEntry(name)); write(content.toByteArray(Charsets.UTF_8)); closeEntry()
}

/** Minimal, dependency-free Word writer (no Apache POI, which is too heavy for Android). */
object DocxWriter {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun build(spec: DocSpec): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.put("[Content_Types].xml", "$XML<Types xmlns=\"$CT_NS\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>")
            z.put("_rels/.rels", "$XML<Relationships xmlns=\"$REL_NS\"><Relationship Id=\"rId1\" Type=\"$OD/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
            z.put("word/_rels/document.xml.rels", "$XML<Relationships xmlns=\"$REL_NS\"><Relationship Id=\"rId1\" Type=\"$OD/styles\" Target=\"styles.xml\"/></Relationships>")
            z.put("word/styles.xml", styles())
            z.put("word/document.xml", document(spec))
        }
        return bos.toByteArray()
    }

    private fun run(text: String) = "<w:r><w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r>"
    private fun para(text: String, style: String? = null, ind: String = ""): String {
        val st = if (style != null) "<w:pStyle w:val=\"$style\"/>" else ""
        return "<w:p><w:pPr>$st$ind</w:pPr>${run(text)}</w:p>"
    }

    private fun document(spec: DocSpec): String {
        val body = StringBuilder(para(spec.title, "Title"))
        for (b in spec.blocks) when (b) {
            is Block.Heading -> body.append(para(b.text, "Heading1"))
            is Block.Para -> body.append(para(b.text))
            is Block.Bullets -> b.items.forEach { body.append(para("• $it", ind = "<w:ind w:left=\"567\" w:hanging=\"283\"/>")) }
        }
        return "$XML<w:document xmlns:w=\"$W\"><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
    }

    private fun styles() = "$XML<w:styles xmlns:w=\"$W\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Nirmala UI\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:b/><w:bCs/><w:sz w:val=\"44\"/><w:szCs w:val=\"44\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:bCs/><w:sz w:val=\"30\"/><w:szCs w:val=\"30\"/></w:rPr></w:style></w:styles>"
}

object XlsxWriter {
    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val number = Regex("^-?(0|[1-9]\\d{0,14})(\\.\\d{1,10})?$")

    fun colName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do { sb.append('A' + n % 26); n = n / 26 - 1 } while (n >= 0)
        return sb.reverse().toString()
    }

    fun sheetNames(raw: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return raw.mapIndexed { i, r ->
            var base = r.replace(Regex("[\\\\/?*\\[\\]:]"), "_").trim().take(28).ifEmpty { "Sheet${i + 1}" }
            var name = base; var k = 2
            while (!used.add(name.lowercase())) { name = "$base$k"; k++ }
            name
        }
    }

    fun build(spec: SheetSpec): ByteArray {
        val names = sheetNames(spec.sheets.map { it.name })
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            val overrides = spec.sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }
            z.put("[Content_Types].xml", "$XML<Types xmlns=\"$CT_NS\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>$overrides</Types>")
            z.put("_rels/.rels", "$XML<Relationships xmlns=\"$REL_NS\"><Relationship Id=\"rId1\" Type=\"$OD/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            z.put("xl/workbook.xml", "$XML<workbook xmlns=\"$S\" xmlns:r=\"$OD\"><sheets>" + names.indices.joinToString("") { "<sheet name=\"${xmlEscape(names[it])}\" sheetId=\"${it + 1}\" r:id=\"rId${it + 1}\"/>" } + "</sheets></workbook>")
            z.put("xl/_rels/workbook.xml.rels", "$XML<Relationships xmlns=\"$REL_NS\">" + names.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"$OD/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } + "</Relationships>")
            spec.sheets.forEachIndexed { i, sh -> z.put("xl/worksheets/sheet${i + 1}.xml", sheet(sh)) }
        }
        return bos.toByteArray()
    }

    private fun sheet(sh: Sheet): String {
        val sb = StringBuilder("$XML<worksheet xmlns=\"$S\"><sheetData>")
        sh.rows.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, v ->
                val ref = colName(c) + (r + 1)
                when {
                    v.isEmpty() -> {}
                    number.matches(v) -> sb.append("<c r=\"$ref\"><v>$v</v></c>")
                    v.startsWith("=") && v.length > 1 -> sb.append("<c r=\"$ref\"><f>${xmlEscape(v.substring(1))}</f></c>")
                    else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(v)}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        return sb.append("</sheetData></worksheet>").toString()
    }
}
