package com.sahachor.app.watch

import android.content.Context
import com.sahachor.app.data.WatchDao
import com.sahachor.app.data.WatchItem
import com.sahachor.app.data.WatchSite
import com.sahachor.app.llm.ChatMsg
import com.sahachor.app.llm.Chatter

data class CheckSummary(val candidates: Int, val announced: Int, val baseline: Boolean, val problem: String?)

/**
 * The Web Watcher. For a site: read the page (and its "next" pages), find documents, remember what is already there,
 * and for anything new read it with the user's own AI key and send a notification.
 * The first scan only memorises what exists (silent), so the user is not flooded with old documents.
 */
class WatchEngine(
    private val ctx: Context,
    private val dao: WatchDao,
    private val chatter: Chatter,
    private val notify: (title: String, text: String, url: String?, id: Int) -> Unit,
) {
    class Crawl(val links: List<Link>, val pages: Int)

    /** Follows "next" links on the same site for up to maxPages pages. */
    suspend fun crawl(startUrl: String, maxPages: Int): Crawl {
        val all = LinkedHashMap<String, Link>()
        val seen = mutableSetOf<String>()
        var next: String? = startUrl
        var pages = 0
        while (pages < maxPages) {
            val current = next ?: break
            if (!seen.add(current)) break
            var parsed: Parsed? = SiteFetcher.html(current)?.let { LinkExtractor.parse(it, current) }
            if (pages == 0 && (parsed == null || parsed.links.size < 3)) {
                val rendered = PageRenderer.render(ctx, current)?.let { LinkExtractor.parse(it, current) }
                if (rendered != null && rendered.links.size > (parsed?.links?.size ?: 0)) parsed = rendered
            }
            val page = parsed ?: break
            page.links.forEach { all.putIfAbsent(it.url, it) }
            pages++
            next = page.next?.takeIf { LinkRules.sameHost(startUrl, it) }
        }
        return Crawl(all.values.toList(), pages)
    }

    private suspend fun analyze(goal: String, title: String, bytes: ByteArray): Analysis? {
        val prompt = AnalysisParser.prompt(goal, title)
        val message: ChatMsg? = when (DocSniffer.kind(bytes)) {
            DocKind.PDF -> PdfPages.renderJpegs(ctx, bytes).takeIf { it.isNotEmpty() }?.let { ChatMsg("user", prompt, it) }
            DocKind.DOCX -> DocxText.extract(bytes).takeIf { it.isNotBlank() }?.let { ChatMsg("user", prompt + "\n\nটেক্সট:\n" + it) }
            DocKind.OTHER -> null
        }
        if (message == null) return null
        val reply = chatter.chat("তুমি নির্ভুল ডকুমেন্ট বিশ্লেষক। শুধু JSON দাও।", listOf(message)).getOrNull() ?: return null
        return AnalysisParser.parse(reply)
    }

    suspend fun checkSite(site: WatchSite): CheckSummary {
        val now = System.currentTimeMillis()
        val crawl = crawl(site.url, MAX_PAGES)
        if (crawl.links.isEmpty()) {
            dao.updateSite(site.copy(lastChecked = now))
            return CheckSummary(0, 0, false, "সাইট থেকে কোনো লিংক পাওয়া যায়নি")
        }
        val candidates = LinkRules.candidates(crawl.links, LinkRules.parseKeywords(site.keywords))
        val known = dao.knownUrls(site.id).toSet()
        val fresh = candidates.filter { it.url !in known }

        if (!site.baselined) {
            for (link in fresh) {
                dao.insertItem(WatchItem(siteId = site.id, url = link.url, title = LinkRules.titleFor(link), subject = null, itemDate = null, summary = null, discoveredAt = now, notified = true, baseline = true))
            }
            dao.updateSite(site.copy(baselined = true, lastChecked = now))
            return CheckSummary(candidates.size, 0, true, null)
        }

        var announced = 0
        for (link in fresh.take(MAX_NEW_PER_RUN)) { // the rest stay unseen and are picked up on the next run
            val title = LinkRules.titleFor(link)
            val bytes = if (LinkRules.isDocument(link.url)) SiteFetcher.download(link.url) else null
            val analysis = bytes?.let { analyze(site.goal, title, it) }
            val notRelevant = analysis?.relevant == false
            val item = WatchItem(
                siteId = site.id, url = link.url, title = title, subject = analysis?.subject, itemDate = analysis?.date, summary = analysis?.summary,
                discoveredAt = System.currentTimeMillis(), notified = true, baseline = notRelevant, // not relevant: remembered, never announced
            )
            val id = dao.insertItem(item)
            if (id == -1L || notRelevant) continue
            announced++
            val head = analysis?.subject ?: title
            val text = analysis?.summary ?: "নতুন ফাইল পাওয়া গেছে। (AI সারাংশ পাওয়া যায়নি)"
            notify("🔔 ${site.name}: $head", text.take(400), link.url, id.toInt())
        }
        dao.updateSite(site.copy(lastChecked = System.currentTimeMillis()))
        return CheckSummary(candidates.size, announced, false, null)
    }

    /** One-off search: look at the site now and answer a question from the best matching documents. */
    suspend fun searchOnce(url: String, question: String, maxDocs: Int = 5): String {
        val crawl = crawl(url, 2)
        if (crawl.links.isEmpty()) return "সাইট থেকে কোনো লিংক পাওয়া যায়নি (শুধু https লিংক চলে; কিছু সাইট স্বয়ংক্রিয় পড়া আটকায়)।"
        val ranked = LinkRules.rank(crawl.links, LinkRules.keywordsFromQuestion(question)).take(maxDocs)
        if (ranked.isEmpty()) return "সাইটে ${crawl.links.size}টি লিংক আছে, কিন্তু কোনো ডকুমেন্ট বা মিলে যাওয়া লিংক পাইনি।"
        val sb = StringBuilder("সাইটে ${crawl.links.size}টি লিংক পাওয়া গেছে; সবচেয়ে মিলে যাওয়া ${ranked.size}টি:\n")
        ranked.forEachIndexed { i, link ->
            val title = LinkRules.titleFor(link)
            sb.append("${i + 1}. $title\nলিংক: ${link.url}\n")
            val bytes = if (LinkRules.isDocument(link.url)) SiteFetcher.download(link.url) else null
            val a = bytes?.let { analyze(question, title, it) }
            if (a != null) sb.append("বিষয়: ${a.subject ?: "-"} · তারিখ: ${a.date ?: "-"}\nসারাংশ: ${a.summary ?: "-"}\n")
        }
        return sb.toString()
    }

    companion object {
        const val MAX_PAGES = 5
        const val MAX_NEW_PER_RUN = 5
    }
}
