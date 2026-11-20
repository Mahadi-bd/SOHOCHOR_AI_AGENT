package com.sahachor.app.watch

import com.sahachor.app.docs.DocxWriter
import com.sahachor.app.docs.SpecParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchRulesTest {
    private val page = """<html><head><title>আদেশ</title></head><body>
        <a href="/files/a.pdf">অফিস আদেশ ১</a><a href="b.docx">দরপত্র</a><a href="https://x.com/page?id=1">নিউজ</a>
        <a href="javascript:void(0)">js</a><a href="mailto:a@b.c">mail</a><a href="?page=2">পরবর্তী »</a></body></html>"""

    @Test fun linksAreResolvedAndNextPageIsFound() {
        val p = LinkExtractor.parse(page, "https://site.gov.bd/notices/")
        val urls = p.links.map { it.url }
        assertTrue("https://site.gov.bd/files/a.pdf" in urls)
        assertTrue("https://site.gov.bd/notices/b.docx" in urls)
        assertTrue("https://x.com/page?id=1" in urls)
        assertTrue(urls.none { it.startsWith("javascript") || it.startsWith("mailto") })
        assertEquals("অফিস আদেশ ১", p.links.first { it.url.endsWith("a.pdf") }.text)
        assertEquals("https://site.gov.bd/notices/?page=2", p.next)
        assertEquals("আদেশ", p.title)
    }

    @Test fun nextButtonTextsAreRecognised() {
        listOf("Next", "next »", "পরবর্তী", "পরবর্তী পাতা", "»", "›").forEach { assertTrue(it, LinkExtractor.isNextText(it)) }
        assertFalse(LinkExtractor.isNextText("নিউজ"))
    }

    @Test fun documentsAreDetectedByExtensionOrDownloadPath() {
        assertTrue(LinkRules.isDocument("https://s.gov.bd/a/b.PDF?x=1"))
        assertTrue(LinkRules.isDocument("https://s.gov.bd/download/123"))
        assertTrue(LinkRules.isDocument("https://s.gov.bd/uploads/2026/x.docx"))
        assertFalse(LinkRules.isDocument("https://s.gov.bd/about"))
    }

    @Test fun keywordsPickExtraPagesAndRankFirst() {
        val links = listOf(Link("https://s/a.pdf", "আদেশ"), Link("https://s/news/1", "দরপত্র বিজ্ঞপ্তি"), Link("https://s/about", "About"))
        val kw = listOf("দরপত্র")
        assertEquals(listOf("https://s/a.pdf", "https://s/news/1"), LinkRules.candidates(links, kw).map { it.url })
        assertEquals("https://s/news/1", LinkRules.rank(links, kw).first().url)
        assertEquals(listOf("দরপত্র", "নিয়োগ", "পরীক্ষা", "ফল"), LinkRules.parseKeywords("দরপত্র, নিয়োগ ; পরীক্ষা\nফল"))
    }

    @Test fun dueTimeHasSlackAndNewSitesAreAlwaysDue() {
        val now = 10_000_000L
        assertTrue(LinkRules.isDue(null, 60, now))
        assertFalse(LinkRules.isDue(now - 30 * 60_000L, 60, now))
        assertTrue(LinkRules.isDue(now - 59 * 60_000L, 60, now))
    }

    @Test fun titlesComeFromTextOrDecodedFileName() {
        assertEquals("অফিস আদেশ", LinkRules.titleFor(Link("https://s/a.pdf", "অফিস আদেশ")))
        val t = LinkRules.titleFor(Link("https://s/files/%E0%A6%86%E0%A6%A6%E0%A7%87%E0%A6%B6.pdf", ""))
        assertTrue(t.endsWith(".pdf") && !t.contains("%"))
        assertTrue(LinkRules.sameHost("https://a.gov.bd/x", "https://A.gov.bd/y?z"))
        assertFalse(LinkRules.sameHost("https://a.gov.bd/x", "https://b.gov.bd/x"))
    }

    @Test fun aiAnswerIsReadEvenWithWordsAroundIt() {
        val a = AnalysisParser.parse("ঠিক আছে {\"subject\":\"বিদ্যুৎ বিল\",\"date\":\"05/11/2026\",\"summary\":\"সারাংশ { এখানে }\",\"relevant\":true} শেষ")!!
        assertEquals("বিদ্যুৎ বিল", a.subject); assertEquals("05/11/2026", a.date); assertEquals("সারাংশ { এখানে }", a.summary); assertEquals(true, a.relevant)
        val b = AnalysisParser.parse("{\"subject\":\"x\",\"date\":null,\"relevant\":false}")!!
        assertNull(b.date); assertNull(b.summary); assertEquals(false, b.relevant)
        assertNull(AnalysisParser.parse("কিছু বুঝিনি")); assertNull(AnalysisParser.parse("{ broken"))
        assertNull(AnalysisParser.parse("{\"subject\":\"x\"}")!!.relevant)
    }

    @Test fun wordFilesAreReadAndPdfsAreRecognised() {
        val spec = SpecParser.doc(JSONObject("""{"title":"রিপোর্ট শিরোনাম","sections":[{"heading":"ক","body":"প্রথম লাইন & দ্বিতীয়"}]}"""))!!
        val docx = DocxWriter.build(spec)
        val text = DocxText.extract(docx)
        assertTrue(text.contains("রিপোর্ট শিরোনাম") && text.contains("প্রথম লাইন & দ্বিতীয়"))
        assertEquals(DocKind.DOCX, DocSniffer.kind(docx))
        assertEquals(DocKind.PDF, DocSniffer.kind("%PDF-1.7 ...".toByteArray()))
        assertEquals(DocKind.OTHER, DocSniffer.kind(ByteArray(20) { 7 }))
        assertEquals("", DocxText.extract(ByteArray(10)))
    }

    @Test fun browserResultIsDecoded() {
        assertEquals("<html>hi", PageRenderer.decode("\"\\u003Chtml>hi\""))
        assertNull(PageRenderer.decode("null"))
        assertNull(PageRenderer.decode(null))
    }
}
