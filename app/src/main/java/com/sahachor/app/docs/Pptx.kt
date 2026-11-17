package com.sahachor.app.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipOutputStream

private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val NS = "xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\""
private const val GRP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
private const val EMU_W = 12192000
private const val EMU_H = 6858000

/** 16:9 presentations: a coloured title slide plus clean content slides. The package structure was validated with python-pptx and LibreOffice. */
object PptxWriter {
    fun build(deck: DeckSpec, logo: Logo? = null): ByteArray {
        val pal = Palette.of(deck.theme)
        val useLogo = if (deck.branded) logo else null
        val n = deck.slides.size + 1
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            val overrides = mutableListOf(
                "/ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
                "/ppt/slideMasters/slideMaster1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml",
                "/ppt/slideLayouts/slideLayout1.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml",
                "/ppt/theme/theme1.xml" to "application/vnd.openxmlformats-officedocument.theme+xml",
            )
            for (i in 1..n) overrides += "/ppt/slides/slide$i.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
            z.put("[Content_Types].xml", contentTypes(*overrides.toTypedArray(), extraDefaults = if (useLogo != null) PNG_DEFAULT else ""))
            z.put("_rels/.rels", relationships(Triple("rId1", "officeDocument", "ppt/presentation.xml")))
            val ids = (0 until n).joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 3}\"/>" }
            z.put("ppt/presentation.xml", XML_HEAD + "<p:presentation $NS><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>$ids</p:sldIdLst><p:sldSz cx=\"$EMU_W\" cy=\"$EMU_H\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
            val slideRels = (0 until n).map { Triple("rId${it + 3}", "slide", "slides/slide${it + 1}.xml") }
            z.put("ppt/_rels/presentation.xml.rels", relationships(Triple("rId1", "slideMaster", "slideMasters/slideMaster1.xml"), Triple("rId2", "theme", "theme/theme1.xml"), *slideRels.toTypedArray()))
            z.put("ppt/theme/theme1.xml", PptxStatic.THEME)
            z.put("ppt/slideMasters/slideMaster1.xml", PptxStatic.MASTER)
            z.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", relationships(Triple("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"), Triple("rId2", "theme", "../theme/theme1.xml")))
            z.put("ppt/slideLayouts/slideLayout1.xml", PptxStatic.LAYOUT)
            z.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", relationships(Triple("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")))
            z.put("ppt/slides/slide1.xml", titleSlide(deck, pal, useLogo))
            deck.slides.forEachIndexed { i, s -> z.put("ppt/slides/slide${i + 2}.xml", contentSlide(s, pal, i + 2, deck.branded, useLogo)) }
            val slideRel = mutableListOf(Triple("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"))
            if (useLogo != null) slideRel += Triple("rId2", "image", "../media/logo.png")
            for (i in 1..n) z.put("ppt/slides/_rels/slide$i.xml.rels", relationships(*slideRel.toTypedArray()))
            if (useLogo != null) z.putBytes("ppt/media/logo.png", useLogo.png)
        }
        return bos.toByteArray()
    }

    private fun rect(id: Int, name: String, x: Int, y: Int, w: Int, h: Int, fill: String): String =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"$fill\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></p:txBody></p:sp>"

    private fun run(text: String, size: Int, color: String, bold: Boolean = false): String {
        val b = if (bold) " b=\"1\"" else ""
        return "<a:r><a:rPr lang=\"bn-BD\" sz=\"$size\"$b dirty=\"0\"><a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill><a:latin typeface=\"Calibri\"/><a:cs typeface=\"Nirmala UI\"/></a:rPr><a:t>${xmlEscape(text)}</a:t></a:r>"
    }

    private fun titleShape(text: String, x: Int, y: Int, w: Int, h: Int, size: Int, color: String): String =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm></p:spPr><p:txBody><a:bodyPr anchor=\"ctr\"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p>${run(text, size, color, true)}</a:p></p:txBody></p:sp>"

    private fun textBox(id: Int, name: String, x: Int, y: Int, w: Int, h: Int, paras: String, anchor: String = "t"): String =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap=\"square\" anchor=\"$anchor\"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paras</p:txBody></p:sp>"

    private fun slideXml(shapes: String, bg: String? = null): String {
        val bgx = if (bg != null) "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"$bg\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>" else ""
        return XML_HEAD + "<p:sld $NS><p:cSld>$bgx<p:spTree>$GRP$shapes</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    private fun picture(id: Int, x: Int, y: Int, w: Int, h: Int): String =
        """<p:pic><p:nvPicPr><p:cNvPr id="$id" name="Logo" descr="LIFE EASY"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""

    private fun badge(id: Int, x: Int, y: Int, w: Int, h: Int): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="Logo badge"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm><a:prstGeom prst="roundRect"><a:avLst><a:gd name="adj" fmla="val 22000"/></a:avLst></a:prstGeom><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang="en-US"/></a:p></p:txBody></p:sp>"""

    private fun titleSlide(deck: DeckSpec, pal: Pal, logo: Logo?): String {
        val sb = StringBuilder()
        var titleY = 1500000
        var titleH = 1800000
        if (deck.branded) {
            val bh = 900000
            var textX = 609600
            if (logo != null) {
                val ph = 720000
                val pw = (ph.toLong() * logo.width / logo.height).toInt()
                val bw = pw + 180000
                sb.append(badge(20, 609600, 600000, bw, bh))
                sb.append(picture(21, 609600 + 90000, 600000 + 90000, pw, ph))
                textX = 609600 + bw + 200000
            }
            sb.append(textBox(22, "Brand", textX, 600000, 5000000, bh, "<a:p>${run(Branding.NAME, 2400, "FFFFFF", true)}</a:p>", "ctr"))
            titleY = 1700000
            titleH = 1600000
        }
        sb.append(rect(10, "Accent bar", 609600, 3400000, 1800000, 70000, "FFFFFF"))
        sb.append(titleShape(deck.title, 609600, titleY, 10972800, titleH, 4400, "FFFFFF"))
        if (deck.subtitle.isNotEmpty()) sb.append(textBox(3, "Subtitle", 609600, 3600000, 10972800, 1200000, "<a:p>${run(deck.subtitle, 2400, "FFFFFF")}</a:p>"))
        return slideXml(sb.toString(), pal.primary)
    }

    private fun contentSlide(slide: Slide, pal: Pal, number: Int, branded: Boolean, logo: Logo?): String {
        val sb = StringBuilder(rect(10, "Header band", 0, 0, EMU_W, 1371600, pal.light))
        sb.append(rect(11, "Accent", 0, 0, 182880, 1371600, pal.primary))
        sb.append(titleShape(slide.title, 609600, 182880, 10972800, 1005840, 3200, pal.primary))
        val paras = slide.bullets.joinToString("") { b ->
            "<a:p><a:pPr marL=\"342900\" indent=\"-342900\"><a:spcBef><a:spcPts val=\"900\"/></a:spcBef><a:buClr><a:srgbClr val=\"${pal.primary}\"/></a:buClr><a:buFont typeface=\"Arial\"/><a:buChar char=\"&#8226;\"/></a:pPr>${run(b, 2200, "1F2937")}</a:p>"
        }
        sb.append(textBox(3, "Content", 609600, 1700000, 10972800, 4400000, paras.ifEmpty { "<a:p><a:endParaRPr lang=\"en-US\"/></a:p>" }))
        sb.append(textBox(12, "Page number", 10500000, 6350000, 1100000, 350000, "<a:p><a:pPr algn=\"r\"/>${run(number.toString(), 1200, "6B7280")}</a:p>", "ctr"))
        if (branded) {
            val ph = 330000
            var textX = 609600
            if (logo != null) {
                val pw = (ph.toLong() * logo.width / logo.height).toInt()
                sb.append(picture(30, 609600, 6330000, pw, ph))
                textX = 609600 + pw + 100000
            }
            sb.append(textBox(31, "Brand", textX, 6330000, 3000000, ph, "<a:p>${run(Branding.NAME, 1200, "6B7280", true)}</a:p>", "ctr"))
        }
        return slideXml(sb.toString())
    }
}
