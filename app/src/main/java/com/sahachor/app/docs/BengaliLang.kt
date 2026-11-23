package com.sahachor.app.docs

/**
 * Light, safe Bengali clean-up for generated documents: common spelling slips, spacing before the danda, and mixed
 * sadhu/cholit verb forms. Ideas come from the owner's Python "Bengali office document agent"; changed on purpose:
 *  - Word boundaries use Bengali-aware look-arounds. Python/Java "\b" treats vowel signs as non-letters, so the old
 *    rules for words ending in ী, ে, া never matched at all.
 *  - No "tone" rewriting. Rules such as টাকা to অর্থ or adding "বিনীতভাবে" after every "আমি" would corrupt amounts and sentences.
 *  - Only strong verb forms are converted, never ambiguous words like "তার" (his/her or wire).
 */
object BengaliLang {
    private const val LETTER = "[\\p{L}\\p{M}\\p{N}]"
    private val bengali = Regex("[\\u0980-\\u09FF]")

    private fun startRegex(w: String) = Regex("(?<!" + LETTER + ")" + Regex.escape(w))
    private fun wordRegex(w: String) = Regex("(?<!" + LETTER + ")" + Regex.escape(w) + "(?!" + LETTER + ")")

    /** Misspellings that cannot be part of a correct word, replaced anywhere. */
    private val stems = listOf(
        "সহযোগীতা" to "সহযোগিতা", "অত্যান্ত" to "অত্যন্ত", "ব্যাক্তি" to "ব্যক্তি", "ব্যাবস্থা" to "ব্যবস্থা",
        "ব্যাবহার" to "ব্যবহার", "পরিস্কার" to "পরিষ্কার", "মুল্যবান" to "মূল্যবান", "প্রয়োজনিয়" to "প্রয়োজনীয়",
    )

    /** Words written with ী that the modern standard writes with ি; matched at the start of a word so endings like -র, -তে still work. */
    private val prefixes = listOf(
        "জরুরী" to "জরুরি", "বেশী" to "বেশি", "বাড়ী" to "বাড়ি", "গাড়ী" to "গাড়ি", "কোম্পানী" to "কোম্পানি", "সরকারী" to "সরকারি",
    )
    private val prefixRules = prefixes.map { (w, r) -> startRegex(w) to r }

    /** Strong sadhu verb forms and their cholit equivalents. */
    private val sadhuToCholit = listOf(
        "হইয়াছে" to "হয়েছে", "হইতেছে" to "হচ্ছে", "হইবে" to "হবে", "হইল" to "হলো", "হইয়া" to "হয়ে",
        "করিতেছি" to "করছি", "করিতেছেন" to "করছেন", "করিয়াছি" to "করেছি", "করিয়াছেন" to "করেছেন", "করিতে" to "করতে",
        "করিবে" to "করবে", "করিব" to "করব", "করিয়া" to "করে", "লইয়া" to "নিয়ে", "যাইবে" to "যাবে", "যাইতে" to "যেতে",
        "আসিবে" to "আসবে", "আসিতে" to "আসতে", "বলিয়া" to "বলে", "বলিবে" to "বলবে", "দেখিয়া" to "দেখে", "জানাইতেছি" to "জানাচ্ছি",
    )
    private val sadhuRules = sadhuToCholit.map { (w, r) -> wordRegex(w) to r }

    private val sadhuMarkers = sadhuToCholit.map { it.first }.filter { it in setOf("হইয়াছে", "হইতেছে", "হইবে", "হইল", "করিতে", "করিবে", "লইয়া", "যাইবে", "যাইতে", "করিয়া", "আসিবে") }
    private val cholitMarkers = listOf("হয়েছে", "হচ্ছে", "হবে", "হলো", "করতে", "করবে", "নেওয়া", "নেবে", "যাবে", "যেতে")

    class Stats { var spelling = 0; var converted = 0 }

    fun hasBengali(text: String) = bengali.containsMatchIn(text)

    /** Sadhu and cholit words found in the text (before any change). */
    fun markers(text: String): Pair<List<String>, List<String>> {
        val s = sadhuMarkers.filter { wordRegex(it).containsMatchIn(text) }
        val c = cholitMarkers.filter { wordRegex(it).containsMatchIn(text) }
        return Pair(s, c)
    }

    /** register: "cholit" also converts strong sadhu verb forms; "sadhu" never rewrites (the AI is asked to write it). */
    fun polishText(text: String, register: String, stats: Stats = Stats()): String {
        if (!hasBengali(text)) return text
        var t = text.replace(Regex("\\s+।"), "।").replace(Regex("।{2,}"), "।").replace(Regex("[ \\t]{2,}"), " ")
        for ((w, r) in stems) {
            val n = t.split(w).size - 1
            if (n > 0) { stats.spelling += n; t = t.replace(w, r) }
        }
        for ((re, r) in prefixRules) t = re.replace(t) { stats.spelling++; r }
        if (register == "cholit") for ((re, r) in sadhuRules) t = re.replace(t) { stats.converted++; r }
        return t
    }

    class Report(val spelling: Int, val converted: Int, val sadhuFound: List<String>, val cholitFound: List<String>) {
        val mixed: Boolean get() = sadhuFound.isNotEmpty() && cholitFound.isNotEmpty()

        /** One short Bengali line for the tool result, or null when nothing needed attention. */
        fun message(register: String): String? {
            val parts = mutableListOf<String>()
            if (spelling > 0) parts += "${spelling}টি বানান ঠিক করা হয়েছে"
            if (converted > 0) parts += "${converted}টি সাধু রূপ চলিত করা হয়েছে"
            if (register == "sadhu" && cholitFound.isNotEmpty()) parts += "সাধু ভাষায় চলিত রূপ আছে (${cholitFound.take(4).joinToString(", ")}); একই ভাষায় আবার লিখতে হবে"
            if (register == "cholit" && mixed) parts += "সাধু-চলিত মিশ্রণ ছিল (${sadhuFound.take(4).joinToString(", ")}) এবং চলিতে একরকম করা হয়েছে"
            return if (parts.isEmpty()) null else "ভাষা যাচাই: " + parts.joinToString("; ") + "।"
        }
    }

    private fun allText(spec: DocSpec): String = buildString {
        append(spec.title).append('\n')
        spec.blocks.forEach { b ->
            when (b) {
                is Block.Heading -> append(b.text).append('\n')
                is Block.Para -> append(b.text).append('\n')
                is Block.Bullets -> b.items.forEach { append(it).append('\n') }
                is Block.Table -> b.rows.forEach { r -> r.forEach { append(it).append(' ') }; append('\n') }
            }
        }
    }

    fun polishDoc(spec: DocSpec, register: String): Pair<DocSpec, Report> {
        val (sadhu, cholit) = markers(allText(spec))
        val st = Stats()
        fun p(t: String) = polishText(t, register, st)
        val blocks = spec.blocks.map { b ->
            when (b) {
                is Block.Heading -> Block.Heading(p(b.text))
                is Block.Para -> Block.Para(p(b.text))
                is Block.Bullets -> Block.Bullets(b.items.map { p(it) })
                is Block.Table -> Block.Table(b.rows.map { r -> r.map { p(it) } })
            }
        }
        return Pair(spec.copy(title = p(spec.title), blocks = blocks), Report(st.spelling, st.converted, sadhu, cholit))
    }

    fun polishDeck(spec: DeckSpec, register: String): Pair<DeckSpec, Report> {
        val text = (listOf(spec.title, spec.subtitle) + spec.slides.flatMap { listOf(it.title) + it.bullets }).joinToString("\n")
        val (sadhu, cholit) = markers(text)
        val st = Stats()
        fun p(t: String) = polishText(t, register, st)
        val slides = spec.slides.map { Slide(p(it.title), it.bullets.map { b -> p(b) }) }
        return Pair(spec.copy(title = p(spec.title), subtitle = p(spec.subtitle), slides = slides), Report(st.spelling, st.converted, sadhu, cholit))
    }
}
