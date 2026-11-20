package com.sahachor.app.watch

import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream
import com.sahachor.app.vault.readLimited

data class Link(val url: String, val text: String)
data class Parsed(val links: List<Link>, val next: String?, val title: String)
enum class DocKind { PDF, DOCX, OTHER }

/** Reads a page: every link with its text, and where the "next page" link points. */
object LinkExtractor {
    private val nextWords = setOf("next", "next »", "next >", "পরবর্তী", "পরবর্তী »", "»", "›", ">", ">>", "»»")

    fun isNextText(t: String): Boolean {
        val x = t.trim().lowercase()
        return x in nextWords || x.startsWith("next ") || x.startsWith("পরবর্তী")
    }

    fun parse(html: String, baseUrl: String): Parsed {
        val doc = Jsoup.parse(html, baseUrl)
        val links = doc.select("a[href]").mapNotNull { a ->
            val u = a.absUrl("href").substringBefore('#')
            if (!u.startsWith("https://") && !u.startsWith("http://")) null
            else Link(u, a.text().trim().ifEmpty { a.attr("title").trim() })
        }.distinctBy { it.url }
        val relNext = (doc.select("link[rel=next]") + doc.select("a[rel=next]")).firstOrNull()?.absUrl("href")?.takeIf { it.isNotBlank() }
        val next = relNext ?: links.firstOrNull { isNextText(it.text) }?.url
        return Parsed(links, next, doc.title())
    }
}

/** Decides which links are "documents", which match the user's words, and when a site is due for a check. */
object LinkRules {
    private val docExt = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "zip", "rar")
    private val docPath = Regex("/(download|attachments?|files?|uploads?)(/|$|\\?)", RegexOption.IGNORE_CASE)

    private fun pathOf(url: String): String = "/" + url.substringAfter("://").substringAfter('/', "")

    fun extension(url: String): String = url.substringBefore('?').substringAfterLast('/', "").substringAfterLast('.', "").lowercase()

    fun isDocument(url: String): Boolean = extension(url) in docExt || docPath.containsMatchIn(pathOf(url))

    fun parseKeywords(csv: String): List<String> =
        csv.split(',', '\n', '،', ';').map { it.trim() }.filter { it.isNotEmpty() }.take(10)

    fun matches(link: Link, keywords: List<String>): Boolean =
        keywords.isNotEmpty() && keywords.any { (link.text + " " + link.url).contains(it, ignoreCase = true) }

    /** What counts as an item on a watched site: every document link, plus pages whose text matches a keyword. */
    fun candidates(links: List<Link>, keywords: List<String>): List<Link> =
        links.filter { isDocument(it.url) || matches(it, keywords) }

    /** Best matches first (keyword hits, then documents); page order is kept for ties because sites list the newest first. */
    fun rank(links: List<Link>, keywords: List<String>): List<Link> {
        fun score(l: Link): Int {
            val hits = keywords.count { (l.text + " " + l.url).contains(it, ignoreCase = true) }
            return hits * 3 + (if (isDocument(l.url)) 2 else 0)
        }
        return links.filter { isDocument(it.url) || matches(it, keywords) }.sortedByDescending { score(it) }
    }

    fun keywordsFromQuestion(q: String): List<String> =
        q.split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 3 }.distinct().take(6)

    fun titleFor(link: Link): String {
        if (link.text.isNotBlank()) return link.text.take(140)
        val name = link.url.substringBefore('?').substringAfterLast('/')
        val decoded = try { URLDecoder.decode(name, "UTF-8") } catch (e: Exception) { name }
        return decoded.ifBlank { link.url }.take(140)
    }

    fun sameHost(a: String, b: String): Boolean =
        a.substringAfter("://").substringBefore('/').lowercase() == b.substringAfter("://").substringBefore('/').lowercase()

    /** A small slack because background checks never fire to the exact minute. */
    fun isDue(lastChecked: Long?, intervalMin: Int, now: Long): Boolean =
        lastChecked == null || now - lastChecked >= intervalMin * 60_000L - 120_000L
}

object DocxText {
    private val textRun = Regex("<w:t[^>]*>([^<]*)</w:t>")

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Plain text of a .docx file, one paragraph per line. Empty if it is not a Word file. */
    fun extract(bytes: ByteArray, max: Int = 12_000): String {
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name == "word/document.xml") {
                        val xml = readLimited(z, 8 * 1024 * 1024)?.toString(Charsets.UTF_8) ?: return ""
                        val sb = StringBuilder()
                        xml.split("</w:p>").forEach { para ->
                            val line = textRun.findAll(para).joinToString("") { unescape(it.groupValues[1]) }.trim()
                            if (line.isNotEmpty()) sb.append(line).append('\n')
                        }
                        return sb.toString().take(max)
                    }
                }
            }
        } catch (e: Exception) { /* not a zip */ }
        return ""
    }
}

/** Looks at the first bytes, because many sites serve PDFs from links without ".pdf". */
object DocSniffer {
    fun kind(bytes: ByteArray): DocKind {
        if (bytes.size > 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-") return DocKind.PDF
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && DocxText.extract(bytes, 1).isNotEmpty()) return DocKind.DOCX
        return DocKind.OTHER
    }
}

data class Analysis(val subject: String?, val date: String?, val summary: String?, val relevant: Boolean?)

/** The AI is asked for a small JSON answer; this reads it even if the model adds words around it. */
object AnalysisParser {
    fun prompt(goal: String, title: String): String = """নিচের ডকুমেন্ট (ছবি বা লেখা) পড়ে শুধু একটি JSON দাও:
{"subject":"বিষয় (বাংলায়, এক লাইনে)","date":"DD/MM/YYYY অথবা null","summary":"৩-৫ লাইনের সারাংশ বাংলায়","relevant":true অথবা false}
ব্যবহারকারীর লক্ষ্য: $goal
ফাইলের নাম: $title
লক্ষ্যের সাথে সম্পর্কিত হলে relevant true। নিশ্চিত না হলে অনুমান করো না, তারিখ না পেলে null দাও।"""

    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    fun parse(reply: String): Analysis? {
        val start = reply.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false; var end = -1
        for (k in start until reply.length) {
            val c = reply[k]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            if (c == '"') inStr = true else if (c == '{') depth++ else if (c == '}') { depth--; if (depth == 0) { end = k; break } }
        }
        if (end < 0) return null
        return try {
            val o = org.json.JSONObject(reply.substring(start, end + 1))
            Analysis(clean(o.optString("subject")), clean(o.optString("date")), clean(o.optString("summary")), if (o.has("relevant") && !o.isNull("relevant")) o.optBoolean("relevant") else null)
        } catch (e: Exception) { null }
    }
}
