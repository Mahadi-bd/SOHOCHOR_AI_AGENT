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
    private fun assertWellFormed(b: ByteArray) { DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(b)) }

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
        val spec = SpecParser.sheets(JSONObject("""{"title":"হিসাব","sheets":[{"name":"খরচ","rows":[["আইটেম","দাম"],["চা",20],["কফি","007"],["মোট","=SUM(B2:B3)"]]}]}"""))!!
        val files = unzip(XlsxWriter.build(spec))
        files.values.forEach { assertWellFormed(it) }
        val sheet = String(files["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)
        assertTrue(sheet.contains("<c r=\"B2\"><v>20</v></c>"))
        assertTrue(sheet.contains("t=\"inlineStr\"") && sheet.contains(">007<"))
        assertTrue(sheet.contains("<f>SUM(B2:B3)</f>"))
    }

    @Test fun columnNamesAndSheetNames() {
        assertEquals("A", XlsxWriter.colName(0)); assertEquals("Z", XlsxWriter.colName(25))
        assertEquals("AA", XlsxWriter.colName(26)); assertEquals("AB", XlsxWriter.colName(27))
        assertEquals(listOf("a_b", "a_b2"), XlsxWriter.sheetNames(listOf("a/b", "A:B")))
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
}
