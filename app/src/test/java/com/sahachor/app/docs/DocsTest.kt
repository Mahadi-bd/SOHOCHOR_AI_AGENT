package com.sahachor.app.docs

import com.sahachor.app.agent.*
import com.sahachor.app.llm.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocsTest {
    private fun unzip(b: ByteArray): Map<String, ByteArray> {
        val m = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(b)).use { z -> while (true) { val e = z.nextEntry ?: break; m[e.name] = z.readBytes() } }
        return m
    }
    private fun assertWellFormed(b: ByteArray) {
        if (b.size >= 8 &&
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() &&
            b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()) return
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(b))
    }

    private val args = JSONObject("""{"format":"docx","title":"মিটিং & পরিকল্পনা","sections":[{"heading":"লক্ষ্য","body":"প্রথম লাইন\nদ্বিতীয় <লাইন>","bullets":["এক","দুই"]}]}""")

    @Test fun docxIsWellFormedAndKeepsBengali() {
        val spec = SpecParser.doc(args)!!
        val files = unzip(DocxWriter.build(spec))
        assertTrue(files.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels")))
        files.values.forEach { assertWellFormed(it) }
        val doc = String(files["word/document.xml"]!!, Charsets.UTF_8)
        assertTrue(doc.contains("মিটিং &amp; পরিকল্পনা"))
        assertTrue(doc.contains("দ্বিতীয় &lt;লাইন&gt;"))
        assertTrue(doc.contains("• এক"))
    }

    @Test fun xlsxHandlesNumbersTextAndFormulas() {
        val spec = SpecParser.sheets(JSONObject("""{"title":"হিসাব","sheets":[{"name":"খরচ","rows":[["আইটেম","দাম"],["চা",20],["কফি","007"],["মোট","=SUM(B2:B3)"]]}]}""") )!!
        val files = unzip(XlsxWriter.build(spec))
        files.values.forEach { assertWellFormed(it) }
        val sheet = String(files["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)
        assertTrue(sheet.contains("<v>20</v>"))
        assertTrue(sheet.contains("<c r=\"A1\" s=\"1\"")) // header row is styled
        assertTrue(sheet.contains("t=\"inlineStr\"") && sheet.contains(">007<"))
        assertTrue(sheet.contains("<f>SUM(B2:B3)</f>"))
    }

    @Test fun columnNamesAndSheetNames() {
        assertEquals("A", XlsxWriter.colName(0)); assertEquals("Z", XlsxWriter.colName(25))
        assertEquals("AA", XlsxWriter.colName(26)); assertEquals("AB", XlsxWriter.colName(27))
        assertEquals(listOf("a_b", "A_B2"), XlsxWriter.sheetNames(listOf("a/b", "A:B"))) // names are unique ignoring case, as Excel requires
    }

    @Test fun specRejectsEmptyContent() {
        assertNull(SpecParser.doc(JSONObject("""{"title":"x"}""")))
        assertNull(SpecParser.doc(JSONObject("""{"sections":[{"heading":"","body":""}]}""")))
        assertNull(SpecParser.sheets(JSONObject("""{"sheets":[]}""")))
    }

    @Test fun fileBaseKeepsBengaliAndRemovesUnsafeChars() {
        assertEquals("বাংলা_রিপোর্ট", SpecParser.fileBase("বাংলা রিপোর্ট"))
        assertEquals("ab", SpecParser.fileBase("a/b\\"))
        assertEquals("document", SpecParser.fileBase("///"))
    }

    @Test fun xmlEscapeDropsIllegalControlChars() = assertEquals("a&amp;b", xmlEscape("a&\u0001b"))

    @Test fun htmlExtractionStripsScriptsAndDecodes() {
        val t = HtmlText.extract("<html><head><title>পরীক্ষা</title><script>evil()</script></head><body><h1>Hello &amp; welcome</h1><p>line&nbsp;two</p><style>.a{}</style></body></html>")
        assertTrue(t.contains("Title: পরীক্ষা")); assertTrue(t.contains("Hello & welcome")); assertTrue(t.contains("line two"))
        assertFalse(t.contains("evil")); assertFalse(t.contains(".a{}"))
    }

    @Test fun urlGuardAllowsOnlyPublicHttps() {
        assertNull(UrlGuard.parse("http://example.com")); assertNull(UrlGuard.parse("file:///etc/passwd")); assertNull(UrlGuard.parse("not a url"))
        assertNotNull(UrlGuard.parse("https://example.com/a?b=1"))
        for (ip in listOf("127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.0.1", "169.254.1.1", "0.0.0.0", "::1", "fd00::1")) assertTrue(ip, UrlGuard.isPrivate(InetAddress.getByName(ip)))
        assertFalse(UrlGuard.isPrivate(InetAddress.getByName("8.8.8.8")))
    }

    @Test fun commandsExpandAndUnknownIsLeftAlone() {
        val e = CommandLibrary.expand("/brainstorm চায়ের দোকান")!!
        assertTrue(e.contains("Brainstorm") && e.endsWith("User input: চায়ের দোকান"))
        assertTrue(CommandLibrary.expand("/meeting")!!.contains("ask the user"))
        assertNull(CommandLibrary.expand("/unknown x")); assertNull(CommandLibrary.expand("hello /brainstorm"))
        assertTrue(CommandLibrary.helpText().contains("/businessplan"))
    }

    @Test fun brokenToolJsonIsRepairedNotShown() = runBlocking {
        val replies = ArrayDeque(listOf("""{"tool":"echo","args":{"a":"unterminated""", "ঠিক আছে"))
        val engine = AgentEngine({ _, _ -> Result.success(replies.removeFirst()) }, emptyList(), { "" }, { _, _ -> })
        assertEquals(AgentStep.Reply("ঠিক আছে"), engine.run(listOf(ChatMsg("user", "hi"))))
    }

    @Test fun offlineErrorsCarryDiagnosticCode() {
        val e = LlmError.Offline("DNS")
        assertTrue(e.fix.contains("[কোড: DNS]")); assertTrue(e.fix.contains("Private DNS"))
    }

    @Test fun pptxIsWellFormedWithTitleSlide() {
        val deck = SpecParser.deck(JSONObject("""{"title":"অ্যাপ প্রস্তাব","subtitle":"ক্লায়েন্ট মিটিং","theme":"orange","slides":[{"title":"ক","bullets":["এক & দুই"]}]}""") )!!
        val files = unzip(PptxWriter.build(deck))
        files.values.forEach { assertWellFormed(it) }
        assertEquals(3, files.keys.count { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) })
        val first = String(files["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue(first.contains("অ্যাপ প্রস্তাব") && first.contains("EA580C"))
        assertTrue(String(files["ppt/slides/slide2.xml"]!!, Charsets.UTF_8).contains("এক &amp; দুই"))
        assertTrue(String(files["ppt/presentation.xml"]!!, Charsets.UTF_8).contains("<p:sldId id=\"258\""))
    }

    @Test fun deckSplitsLongBulletListsAndRejectsEmpty() {
        val bullets = (1..8).joinToString(",") { "\"পয়েন্ট $it\"" }
        val deck = SpecParser.deck(JSONObject("""{"title":"t","slides":[{"title":"তালিকা","bullets":[$bullets]}]}""") )!!
        assertEquals(listOf("তালিকা", "তালিকা (2)"), deck.slides.map { it.title })
        assertEquals(listOf(6, 2), deck.slides.map { it.bullets.size })
        assertNull(SpecParser.deck(JSONObject("""{"title":"t","slides":[]}""")))
    }

    @Test fun themesFallBackToBlue() {
        assertEquals("2563EB", Palette.of("nonsense").primary)
        assertEquals("059669", Palette.of(" Green ").primary)
        assertEquals("blue", Palette.normalize(null))
    }

    @Test fun docxRendersTablesWithThemeColours() {
        val spec = SpecParser.doc(JSONObject("""{"theme":"green","title":"রিপোর্ট","sections":[{"heading":"সারণি","table":[["ধাপ","তারিখ"],["ডিজাইন","2024-01-01"]]}]}""") )!!
        val doc = String(unzip(DocxWriter.build(spec))["word/document.xml"]!!, Charsets.UTF_8)
        assertTrue(doc.contains("<w:tbl>") && doc.contains("w:fill=\"059669\"") && doc.contains("<w:tblHeader/>"))
        assertWellFormed(doc.toByteArray())
    }

    @Test fun phoneNumbersAreCleaned() {
        assertEquals("+8801712345678", cleanPhone(" +880 1712-345678 "))
        assertEquals("01712345678", cleanPhone("017 1234 5678"))
        assertNull(cleanPhone("12")); assertNull(cleanPhone("abc"))
    }

    @Test fun newCommandsExist() {
        assertTrue(CommandLibrary.expand("/legal আমি অনলাইনে খাবার বিক্রি করব")!!.contains("not legal advice"))
        assertTrue(CommandLibrary.expand("/presentation বিষয়")!!.contains("pptx"))
    }

    @Test fun odtIsValidOpenDocumentPackage() {
        val spec = SpecParser.doc(JSONObject("""{"theme":"teal","title":"মিটিং & পরিকল্পনা","sections":[{"heading":"লক্ষ্য","body":"প্রথম <লাইন>"}]}""") )!!
        val bytes = OdtWriter.build(spec)
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            val first = z.nextEntry!!
            assertEquals("mimetype", first.name); assertEquals(java.util.zip.ZipEntry.STORED, first.method)
            assertEquals("application/vnd.oasis.opendocument.text", String(z.readBytes(), Charsets.UTF_8))
        }
        val files = unzip(bytes)
        listOf("META-INF/manifest.xml", "content.xml", "styles.xml").forEach { assertWellFormed(files[it]!!) }
        val content = String(files["content.xml"]!!, Charsets.UTF_8)
        assertTrue(content.contains("মিটিং &amp; পরিকল্পনা") && content.contains("0D9488") && content.contains("<table:table "))
    }

    @Test fun odsConvertsFormulasAndRejectsDangerousOnes() {
        assertEquals("of:=SUM([.B2:.B3])", OdfFormula.fromExcel("=SUM(B2:B3)"))
        assertEquals("of:=[.B2]*[.C2]", OdfFormula.fromExcel("=B2*C2"))
        assertEquals("of:=AVERAGE([.A1];[.B1])", OdfFormula.fromExcel("=AVERAGE(A1,B1)"))
        assertEquals("of:=LOG10([.A1])", OdfFormula.fromExcel("=LOG10(A1)")) // LOG10 is a function, not a cell
        assertNull(OdfFormula.fromExcel("=HYPERLINK(\"http://x\")")); assertNull(OdfFormula.fromExcel("=A1+Sheet1!B2"))
        val spec = SpecParser.sheets(JSONObject("""{"title":"হিসাব","sheets":[{"name":"খরচ","rows":[["আইটেম","দাম"],["চা",20],["মোট","=SUM(B2:B2)"],["বেতন",30]]}]}""") )!!
        val files = unzip(OdsWriter.build(spec))
        files.filterKeys { it.endsWith(".xml") }.values.forEach { assertWellFormed(it) }
        val c = String(files["content.xml"]!!, Charsets.UTF_8)
        assertTrue(c.contains("table:formula=\"of:=SUM([.B2:.B2])\"") && !c.contains("table:formula=\"of:=WEBSERVICE") && c.contains("office:value=\"20\""))
    }

    @Test fun xlsxTurnsDangerousFormulasIntoText() {
        val spec = SpecParser.sheets(JSONObject("""{"sheets":[{"name":"s","rows":[["a"],["=HYPERLINK(\"x\")"],["=SUM(A1:A1)"]]}]}""") )!!
        val sheet = String(unzip(XlsxWriter.build(spec))["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)
        assertFalse(sheet.contains("<f>HYPERLINK")); assertTrue(sheet.contains("<f>SUM(A1:A1)</f>"))
    }

    @Test fun csvQuotesAndDefusesInjection() {
        val text = String(CsvWriter.build(listOf(listOf("নাম", "মন্তব্য"), listOf("রহিম", "হ্যালো, \"বন্ধু\""), listOf("=CMD()", "-5"), listOf("-[...]", "+8801712345678"), listOf("@a", "+cmd")]]), Charsets.UTF_8)
        assertTrue(text.startsWith("\uFEFF"))
        val lines = text.removePrefix("\uFEFF").split("\r\n")
        assertEquals("নাম,মন্তব্য", lines[0]); assertEquals("রহিম,\"হ্যালো, \"\"বন্ধু\"\"\"", lines[1])
        assertEquals("'=CMD(),-5", lines[2]); assertEquals("'-x,+8801712345678", lines[3]); assertEquals("'@a,'+cmd", lines[4])
    }

    @Test fun brandingIsOnByDefault() { assertTrue(Branding.enabled()); assertEquals("LIFE EASY", Branding.NAME) }

    private val tinyPng = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

    @Test fun logoIsParsedFromPngHeader() {
        val l = Logo.parse(tinyPng)!!
        assertEquals(1, l.width); assertEquals(1, l.height)
        assertNull(Logo.parse(byteArrayOf(1, 2, 3)))
    }

    @Test fun docxCarriesLogoAndBrandNameOnlyWhenBranded() {
        val spec = SpecParser.doc(JSONObject("""{"title":"রিপোর্ট","sections":[{"heading":"ক","body":"খ"}]}""") )!!
        val logo = Logo.parse(tinyPng)
        val files = unzip(DocxWriter.build(spec.copy(branded = true), logo))
        files.values.forEach { assertWellFormed(it) }
        assertTrue(files.containsKey("word/media/logo.png"))
        val doc = String(files["word/document.xml"]!!, Charsets.UTF_8)
        assertTrue(doc.contains("LIFE EASY") && doc.contains("r:embed=\"rId2\""))
        assertTrue(String(files["word/_rels/document.xml.rels"]!!, Charsets.UTF_8).contains("media/logo.png"))
        val plain = unzip(DocxWriter.build(spec.copy(branded = false), logo))
        assertFalse(plain.containsKey("word/media/logo.png"))
        assertFalse(String(plain["word/document.xml"]!!, Charsets.UTF_8).contains("LIFE EASY"))
    }

    @Test fun pptxCarriesLogoOnTitleAndContentSlides() {
        val deck = SpecParser.deck(JSONObject("""{"title":"প্রস্তাব","slides":[{"title":"ক","bullets":["খ"]}]}""") )!!
        val files = unzip(PptxWriter.build(deck.copy(branded = true), Logo.parse(tinyPng)))
        files.values.forEach { assertWellFormed(it) }
        assertTrue(files.containsKey("ppt/media/logo.png"))
        for (n in 1..2) {
            assertTrue(String(files["ppt/slides/slide$n.xml"]!!, Charsets.UTF_8).contains("LIFE EASY"))
            assertTrue(String(files["ppt/slides/_rels/slide$n.xml.rels"]!!, Charsets.UTF_8).contains("../media/logo.png"))
        }
        val plain = unzip(PptxWriter.build(deck.copy(branded = false), Logo.parse(tinyPng)))
        assertFalse(plain.containsKey("ppt/media/logo.png"))
        assertFalse(String(plain["ppt/slides/slide1.xml"]!!, Charsets.UTF_8).contains("LIFE EASY"))
    }

    @Test fun odtCarriesLogoFrame() {
        val spec = SpecParser.doc(JSONObject("""{"title":"রিপোর্ট","sections":[{"heading":"ক","body":"খ"}]}""") )!!
        val files = unzip(OdtWriter.build(spec.copy(branded = true), Logo.parse(tinyPng)))
        assertTrue(files.containsKey("Pictures/logo.png"))
        assertTrue(String(files["content.xml"]!!, Charsets.UTF_8).contains("<draw:frame") && String(files["META-INF/manifest.xml"]!!, Charsets.UTF_8).contains("Pictures/logo.png"))
        files.filterKeys { it.endsWith(".xml") }.values.forEach { assertWellFormed(it) }
    }

    @Test fun requestedFormatsAreMergedAndCleaned() {
        assertEquals(listOf("pdf", "docx", "odt"), Formats.requested(JSONObject("""{"format":"PDF","formats":["docx","pdf"," odt "]}""")))
        assertTrue(Formats.requested(JSONObject("{}"))).isEmpty())
    }

    @Test fun brandingCanOnlyBeSwitchedOffByTheAppNotByContent() {
        assertFalse(Entitlements.isPro)
        assertTrue(Branding.enabled())
    }
}
