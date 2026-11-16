package com.sahachor.app.agent

/** Slash commands: reusable prompt "skills". The chat shows what the user typed; the model receives the expanded instruction. */
object CommandLibrary {
    data class Cmd(val name: String, val help: String, val instruction: String)

    private const val FILE = "Put the complete content in a file with create_document (pdf unless the user asked for another format), then reply with a short summary."

    val all = listOf(
        Cmd(
            "brainstorm",
            "বিষয় নিয়ে আইডিয়ার তালিকা",
            """Brainstorm 10 distinct, concrete ideas on the topic. Group them by theme and give each idea a one-line explanation. Prefer practical, varied ideas over generic suggestions."""
        ),
        Cmd(
            "ideate",
            "একটি আইডিয়া গভীরে নেওয়া",
            """Develop the idea: identify the target user and core value, outline the first 3 low-cost steps to test it, and explain the main risks and how to reduce them."""
        ),
        Cmd(
            "repurpose",
            "এক কনটেন্ট থেকে একাধিক ফরম্যাট",
            """Repurpose the content into a short social post, a longer post, a 30-second script, and 5 alternative headlines. Keep each format suited to its audience and platform."""
        ),
        Cmd(
            "viral",
            "সোশ্যাল মিডিয়া হুক ও পরিকল্পনা",
            """Create 5 attention-grabbing hooks and a simple 7-day posting plan for the topic. Be honest; do not promise virality."""
        ),
        Cmd(
            "businessplan",
            "ব্যবসা পরিকল্পনা (ফাইল)",
            """Write a practical business plan covering the executive summary, problem, solution, market, target customers, revenue model, costs, marketing, operations, risks, and next steps. State assumptions clearly and do not invent market facts.""" + "\n" + FILE
        ),
        Cmd(
            "competitor",
            "প্রতিযোগী বিশ্লেষণ",
            """Analyse the competitors. If the user gave links, read them with fetch_url. Never invent facts: label anything unverifiable, cite the sources used, and distinguish observed facts from analysis. Compare positioning, offerings, strengths, weaknesses, and opportunities."""
        ),
        Cmd(
            "positioning",
            "বাজারে অবস্থান ও বার্তা",
            """Define the positioning: target customer, category, key difference, proof points, one-line positioning statement, and 3 supporting messages. Do not claim proof that the user has not provided."""
        ),
        Cmd(
            "proposal",
            "ক্লায়েন্ট প্রস্তাব (ফাইল)",
            """Write a client proposal covering the client's need, proposed approach, deliverables, timeline, pricing plan, assumptions, and next steps. Mark missing details as questions or assumptions; do not fabricate them.""" + "\n" + FILE
        ),
        Cmd(
            "presentation",
            "প্রেজেন্টেশন স্লাইড (ফাইল)",
            """Create a presentation with create_document format pptx: 6-10 slides, each with a clear title and concise content. Include a logical opening, main points, and conclusion.""" + "\n" + FILE
        ),
        Cmd(
            "legal",
            "আইনি ঝুঁকি যাচাই",
            """The user describes a plan or activity. Assume Bangladesh unless the user names another country. Identify likely legal areas and practical risks, distinguish general information from legal advice, and recommend consulting a qualified local lawyer for decisions. Do not state uncertain legal claims as fact."""
        ),
        Cmd(
            "meeting",
            "মিটিংয়ের প্রস্তুতি (ফাইল)",
            """Prepare a meeting pack with the objective, timed agenda, key talking points, likely questions with suggested answers, and follow-up actions.""" + "\n" + FILE
        )
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
