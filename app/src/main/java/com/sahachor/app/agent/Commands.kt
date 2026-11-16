package com.sahachor.app.agent

/** Slash commands: reusable prompt "skills". The chat shows what the user typed; the model receives the expanded instruction. */
object CommandLibrary {
    data class Cmd(val name: String, val help: String, val instruction: String)

    private const val FILE = "Put the complete content in a file with create_document (pdf unless the user asked for another format), then reply with a short summary."

    val all = listOf(
        Cmd("brainstorm", "বিষয় নিয়ে আইডিয়ার তালিকা", "Brainstorm 10 distinct, concrete ideas on the topic, grouped by theme, each with one line on why it could work."),
        Cmd("ideate", "একটি আইডিয়া গভীরে নেওয়া", "Take the idea and develop it: target user, core value, first 3 steps to test it cheaply, main risks."),
        Cmd("repurpose", "এক কনটেন্ট থেকে একাধিক ফরম্যাট", "Repurpose the content into: a short social post, a longer post, a 30-second script, and 3 key takeaways."),
        Cmd("viral", "সোশ্যাল মিডিয়া হুক ও পরিকল্পনা", "Create 5 scroll-stopping hooks and a simple 7-day posting plan for the topic. Be honest that virality cannot be guaranteed."),
        Cmd("businessplan", "ব্যবসা পরিকল্পনা (ফাইল)", "Write a practical business plan: summary, problem, solution, market, customers, revenue model, costs, marketing, risks, 90-day plan. Mark every assumption clearly. Include a short "Legal considerations" section (general information, not legal advice). $FILE"),
        Cmd("competitor", "প্রতিযোগী বিশ্লেষণ", "Analyse competitors. If the user gave links, read them with fetch_url. Never invent facts: label anything unverified as an assumption. Compare features, pricing, strengths, weaknesses and gaps. $FILE"),
        Cmd("positioning", "বাজারে অবস্থান ও বার্তা", "Define positioning: target customer, category, key difference, proof points, one-line statement, and 3 message variants."),
        Cmd("proposal", "ক্লায়েন্ট প্রস্তাব (ফাইল)", "Write a client proposal: understanding of the need, approach, deliverables, timeline, pricing placeholders, terms to confirm. Do not invent prices or facts. Include a short "Legal considerations" note (general information, not legal advice). $FILE"),
        Cmd("presentation", "প্রেজেন্টেশন স্লাইড (ফাইল)", "Create a presentation with create_document format pptx: 6-10 slides, each with a clear title and 3-5 short bullet points (max about 12 words each), a fitting theme, then reply with a 2-line summary."),
        Cmd("legal", "আইনি ঝুঁকি যাচাই", "The user describes a plan or activity. Assume Bangladesh unless the user names another country. Identify the likely legal areas (for example business registration and trade licence, tax and VAT, copyright and trademarks, data protection and privacy, consumer protection, contracts and employment, app-store or platform policies, sector rules). For each: what to check, a risk level (low, medium, high) and a concrete next step. State clearly that this is general information, not legal advice, that laws change, and that a licensed lawyer should confirm anything important. Never invent law names or section numbers; say when unsure. If the user gave official links, read them with fetch_url."),
        Cmd("meeting", "মিটিংয়ের প্রস্তুতি (ফাইল)", "Prepare a meeting pack: objective, agenda with timings, key talking points, likely questions with answers, decisions needed, follow-up checklist. $FILE"),
    )

    fun helpText(): String = "উপলব্ধ কমান্ড:\n" + all.joinToString("\n") { "/${it.name} <বিষয়> — ${it.help}" }

    /** Returns the expanded prompt, or null when the text is not a known command. */
    fun expand(text: String): String? {
        val t = text.trim()
        if (!t.startsWith("/")) return null
        val name = t.substring(1).substringBefore(' ').lowercase()
        val c = all.firstOrNull { it.name == name } ?: return null
        val arg = t.substringAfter(' ', "").trim().ifEmpty { "(none given: ask the user what the topic is before doing anything else)" }
        return "${c.instruction}\n\nUser input: $arg"
    }
}
