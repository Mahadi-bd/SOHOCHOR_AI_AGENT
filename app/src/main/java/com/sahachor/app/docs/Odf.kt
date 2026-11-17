package com.sahachor.app.docs

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val ODF_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
private const val ODF_NS = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
    "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" " +
    "xmlns:xlink=\"http://www.w3.org/1999/xlink\" xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\" xmlns:of=\"urn:oasis:names:tc:opendocument:xmlns:of:1.2\""

private fun ZipOutputStream.putStored(name: String, data: ByteArray) {
    val e = ZipEntry(name)
    e.method = ZipEntry.STORED
    e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
    val crc = CRC32(); crc.update(data); e.crc = crc.value
    putNextEntry(e); write(data); closeEntry()
}

private fun cm(d: Double): String = String.format(Locale.ROOT, "%.2f", d) // never locale digits or commas inside XML numbers

/** ODF packages need an uncompressed "mimetype" entry first. */
private fun odfPackage(mime: String, content: String, logo: Logo?): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        z.putStored("mimetype", mime.toByteArray(Charsets.US_ASCII))
        val pic = if (logo != null) "<manifest:file-entry manifest:full-path=\"Pictures/logo.png\" manifest:media-type=\"image/png\"/>" else ""
        z.put("META-INF/manifest.xml", ODF_XML + "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" manifest:version=\"1.2\"><manifest:file-entry manifest:full-path=\"/\" manifest:version=\"1.2\" manifest:media-type=\"$mime\"/><manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/><manifest:file-entry manifest:full-path=\"styles.xml\" manifest:media-type=\"text/xml\"/>$pic</manifest:manifest>")
        z.put("styles.xml", ODF_XML + "<office:document-styles $ODF_NS office:version=\"1.2\"><office:styles/></office:document-styles>")
        z.put("content.xml", content)
        if (logo != null) z.putBytes("Pictures/logo.png", logo.png)
    }
    return bos.toByteArray()
}

/** OpenDocument Text (LibreOffice, Google Docs and Word can open it). Same design as the Word file. */
object OdtWriter {
    fun build(spec: DocSpec, logo: Logo? = null): ByteArray {
        val pal = Palette.of(spec.theme)
        val useLogo = if (spec.branded) logo else null
        val p = pal.primary
        val styles = """<style:style style:name="PTitle" style:family="paragraph"><style:paragraph-properties fo:margin-top="0.2cm" fo:margin-bottom="0.5cm" fo:padding-bottom="0.15cm" fo:border-bottom="1.5pt solid #$p"/><style:text-properties fo:color="#$p" fo:font-size="26pt" fo:font-weight="bold" style:font-size-complex="26pt" style:font-weight-complex="bold"/></style:style>""" +
            """<style:style style:name="PH1" style:family="paragraph"><style:paragraph-properties fo:margin-top="0.5cm" fo:margin-bottom="0.2cm" fo:keep-with-next="always"/><style:text-properties fo:color="#$p" fo:font-size="16pt" fo:font-weight="bold" style:font-size-complex="16pt" style:font-weight-complex="bold"/></style:style>""" +
            """<style:style style:name="PBody" style:family="paragraph"><style:paragraph-properties fo:margin-bottom="0.2cm" fo:line-height="140%"/><style:text-properties fo:color="#1F2937" fo:font-size="11pt" style:font-size-complex="11pt"/></style:style>""" +
            """<style:style style:name="PBullet" style:family="paragraph"><style:paragraph-properties fo:margin-left="0.9cm" fo:text-indent="-0.5cm" fo:margin-bottom="0.12cm"/><style:text-properties fo:color="#1F2937" fo:font-size="11pt" style:font-size-complex="11pt"/></style:style>""" +
            """<style:style style:name="TDot" style:family="text"><style:text-properties fo:color="#$p" fo:font-weight="bold"/></style:style>""" +
            """<style:style style:name="TBrand" style:family="text"><style:text-properties fo:color="#$p" fo:font-weight="bold" fo:font-size="13pt" fo:letter-spacing="0.06cm"/></style:style>""" +
            """<style:style style:name="Fr1" style:family="graphic"><style:graphic-properties style:vertical-pos="middle" style:vertical-rel="text" style:horizontal-pos="from-left" style:horizontal-rel="paragraph" fo:margin-right="0.2cm" style:wrap="none"/></style:style>""" +
            """<style:style style:name="TblA" style:family="table"><style:table-properties style:width="17cm" table:align="left" fo:margin-bottom="0.3cm"/></style:style>""" +
            """<style:style style:name="CH" style:family="table-cell"><style:table-cell-properties fo:background-color="#$p" fo:padding="0.12cm" fo:border="0.5pt solid #BFBFBF"/></style:style>""" +
            """<style:style style:name="CZ" style:family="table-cell"><style:table-cell-properties fo:background-color="#${pal.light}" fo:padding="0.12cm" fo:border="0.5pt solid #BFBFBF"/></style:style>""" +
            """<style:style style:name="CN" style:family="table-cell"><style:table-cell-properties fo:padding="0.12cm" fo:border="0.5pt solid #BFBFBF"/></style:style>""" +
            """<style:style style:name="PCellH" style:family="paragraph"><style:text-properties fo:color="#FFFFFF" fo:font-weight="bold" fo:font-size="10.5pt" style:font-weight-complex="bold"/></style:style>""" +
            """<style:style style:name="PCell" style:family="paragraph"><style:text-properties fo:color="#1F2937" fo:font-size="10.5pt"/></style:style>"""
        val body = StringBuilder()
        if (spec.branded) {
            val frame = if (useLogo != null) {
                val h = 1.1
                val w = cm(h * useLogo.width / useLogo.height)
                """<draw:frame draw:style-name="Fr1" draw:name="logo" text:anchor-type="as-char" svg:width="${w}cm" svg:height="${cm(h)}cm"><draw:image xlink:href="Pictures/logo.png" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>"""
            } else ""
            body.append("""<text:p text:style-name="PBody">$frame<text:span text:style-name="TBrand">${Branding.NAME}</text:span></text:p>""")
        }
        body.append("""<text:p text:style-name="PTitle">${xmlEscape(spec.title)}</text:p>""")
        var tableNo = 0
        for (b in spec.blocks) when (b) {
            is Block.Heading -> body.append("""<text:h text:style-name="PH1" text:outline-level="1">${xmlEscape(b.text)}</text:h>""")
            is Block.Para -> body.append("""<text:p text:style-name="PBody">${xmlEscape(b.text)}</text:p>""")
            is Block.Bullets -> b.items.forEach { body.append("""<text:p text:style-name="PBullet"><text:span text:style-name="TDot">•</text:span>  ${xmlEscape(it)}</text:p>""") }
            is Block.Table -> {
                tableNo++
                val n = b.rows.maxOf { it.size }.coerceAtLeast(1)
                val t = StringBuilder("""<table:table table:name="Table$tableNo" table:style-name="TblA"><table:table-column table:number-columns-repeated="$n"/>""")
                b.rows.forEachIndexed { i, r ->
                    val cellStyle = if (i == 0) "CH" else if (i % 2 == 0) "CZ" else "CN"
                    val paraStyle = if (i == 0) "PCellH" else "PCell"
                    val cells = (0 until n).joinToString("") { j ->
                        """<table:table-cell table:style-name="$cellStyle" office:value-type="string"><text:p text:style-name="$paraStyle">${xmlEscape(r.getOrElse(j) { "" })}</text:p></table:table-cell>"""
                    }
                    t.append(if (i == 0) "<table:table-header-rows><table:table-row>$cells</table:table-row></table:table-header-rows>" else "<table:table-row>$cells</table:table-row>")
                }
                body.append(t).append("</table:table>").append("""<text:p text:style-name="PBody"/>""")
            }
        }
        val content = ODF_XML + """<office:document-content $ODF_NS office:version="1.2"><office:automatic-styles>$styles</office:automatic-styles><office:body><office:text>$body</office:text></office:body></office:document-content>"""
        return odfPackage("application/vnd.oasis.opendocument.text", content, useLogo)
    }
}

/** OpenDocument Spreadsheet (LibreOffice, Google Sheets and Excel can open it). */
object OdsWriter {
    private val number = Regex("^-?(0|[1-9]\\d{0,14})(\\.\\d{1,10})?$")

    fun build(spec: SheetSpec): ByteArray {
        val pal = Palette.of(spec.theme)
        val names = XlsxWriter.sheetNames(spec.sheets.map { it.name })
        val styles = StringBuilder(
            """<style:style style:name="ceH" style:family="table-cell"><style:table-cell-properties fo:background-color="#${pal.primary}" fo:border="0.5pt solid #D1D5DB" style:vertical-align="middle" fo:wrap-option="wrap"/><style:text-properties fo:color="#FFFFFF" fo:font-weight="bold" style:font-weight-complex="bold"/></style:style>""" +
                """<style:style style:name="ceZ" style:family="table-cell"><style:table-cell-properties fo:background-color="#${pal.light}" fo:border="0.5pt solid #D1D5DB"/></style:style>""" +
                """<style:style style:name="ceN" style:family="table-cell"><style:table-cell-properties fo:border="0.5pt solid #D1D5DB"/></style:style>"""
        )
        val tables = StringBuilder()
        spec.sheets.forEachIndexed { si, sh ->
            val n = sh.rows.maxOf { it.size }.coerceAtLeast(1)
            val widths = (0 until n).map { j -> minOf(50, maxOf(8, (sh.rows.maxOf { it.getOrElse(j) { "" }.length } * 1.2).toInt() + 2)) }
            widths.forEachIndexed { j, w -> styles.append("""<style:style style:name="co${si}_$j" style:family="table-column"><style:table-column-properties style:column-width="${cm(w * 0.19)}cm"/></style:style>""") }
            tables.append("""<table:table table:name="${xmlEscape(names[si])}">""")
            for (j in 0 until n) tables.append("""<table:table-column table:style-name="co${si}_$j"/>""")
            sh.rows.forEachIndexed { r, row ->
                val cs = if (r == 0) "ceH" else if (r % 2 == 0) "ceZ" else "ceN"
                tables.append("<table:table-row>")
                for (j in 0 until n) {
                    val v = row.getOrElse(j) { "" }
                    val f = if (v.startsWith("=") && v.length > 1) OdfFormula.fromExcel(v) else null
                    when {
                        v.isEmpty() -> tables.append("""<table:table-cell table:style-name="$cs"/>""")
                        number.matches(v) -> tables.append("""<table:table-cell table:style-name="$cs" office:value-type="float" office:value="$v"><text:p>$v</text:p></table:table-cell>""")
                        f != null -> tables.append("""<table:table-cell table:style-name="$cs" table:formula="${xmlEscape(f)}"/>""") // no cached value: the app calculates on open
                        else -> tables.append("""<table:table-cell table:style-name="$cs" office:value-type="string"><text:p>${xmlEscape(v)}</text:p></table:table-cell>""")
                    }
                }
                tables.append("</table:table-row>")
            }
            tables.append("</table:table>")
        }
        val content = ODF_XML + """<office:document-content $ODF_NS office:version="1.2"><office:automatic-styles>$styles</office:automatic-styles><office:body><office:spreadsheet>$tables</office:spreadsheet></office:body></office:document-content>"""
        return odfPackage("application/vnd.oasis.opendocument.spreadsheet", content, null)
    }
}

/** Excel-style =SUM(A1:B2) to ODF "of:=SUM([.A1:.B2])". Returns null for anything unsafe or unsupported (it is then stored as plain text). */
object OdfFormula {
    private val range = Regex("([\$]?[A-Z]{1,3}[\$]?\\d+):([\$]?[A-Z]{1,3}[\$]?\\d+)")
    private val single = Regex("(?<![\\w\\[.:])([\$]?[A-Z]{1,3}[\$]?\\d+)(?![\\w\\](])")

    fun fromExcel(f: String): String? {
        if (!f.startsWith("=") || f.length < 2) return null
        val expr = f.substring(1)
        if (!SafeFormula.isSafe(expr, strict = true)) return null
        var e = range.replace(expr) { m -> "[." + m.groupValues[1] + ":." + m.groupValues[2] + "]" }
        e = single.replace(e) { m -> "[." + m.groupValues[1] + "]" }
        return "of:=" + e.replace(',', ';')
    }
}
