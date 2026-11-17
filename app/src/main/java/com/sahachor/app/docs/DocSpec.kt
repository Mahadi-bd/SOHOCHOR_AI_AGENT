package com.sahachor.app.docs

import org.json.JSONObject

sealed class Block {
    data class Heading(val text: String) : Block()
    data class Para(val text: String) : Block()
    data class Bullets(val items: List<String>) : Block()
    /** First row is the header. */
    data class Table(val rows: List<List<String>>) : Block()
}

data class DocSpec(val title: String, val blocks: List<Block>, val theme: String = "blue", val branded: Boolean = true)
data class Sheet(val name: String, val rows: List<List<String>>)
data class SheetSpec(val title: String, val sheets: List<Sheet>, val theme: String = "blue")
data class Slide(val title: String, val bullets: List<String>)
data class DeckSpec(val title: String, val subtitle: String, val slides: List<Slide>, val theme: String = "blue", val branded: Boolean = true)

data class Pal(val primary: String, val light: String)

/** Colour themes shared by PDF, Word, Excel and PowerPoint so every file from one request looks consistent. */
object Palette {
    private val all = mapOf(
        "blue" to Pal("2563EB", "EAF1FF"), "green" to Pal("059669", "E8F7F0"), "purple" to Pal("7C3AED", "F0EAFE"),
        "orange" to Pal("EA580C", "FFF0E6"), "teal" to Pal("0D9488", "E6F6F5"), "red" to Pal("DC2626", "FDECEC"),
    )
    val names: Set<String> get() = all.keys
    fun of(name: String?): Pal = all[name?.trim()?.lowercase()] ?: all.getValue("blue")
    fun normalize(name: String?): String = name?.trim()?.lowercase()?.takeIf { it in all } ?: "blue"
}

/** Turns the model's JSON arguments into validated specs. Returns null when the content is unusable. */
object SpecParser {
    fun doc(args: JSONObject): DocSpec? {
        val title = args.optString("title").trim().ifEmpty { "ডকুমেন্ট" }
        val secs = args.optJSONArray("sections") ?: return null
        val blocks = mutableListOf<Block>()
        for (i in 0 until secs.length()) {
            val s = secs.optJSONObject(i) ?: continue
            s.optString("heading").trim().takeIf { it.isNotEmpty() }?.let { blocks += Block.Heading(it) }
            s.optString("body").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { blocks += Block.Para(it) }
            val b = s.optJSONArray("bullets")
            if (b != null) {
                val items = (0 until b.length()).map { b.optString(it).trim() }.filter { it.isNotEmpty() }
                if (items.isNotEmpty()) blocks += Block.Bullets(items)
            }
            table(s.optJSONArray("table"))?.let { blocks += Block.Table(it) }
        }
        return if (blocks.isEmpty()) null else DocSpec(title.take(150), blocks, Palette.normalize(args.optString("theme")))
    }

    private fun table(arr: org.json.JSONArray?): List<List<String>>? {
        if (arr == null) return null
        val rows = (0 until minOf(arr.length(), MAX_ROWS)).mapNotNull { r ->
            val row = arr.optJSONArray(r) ?: return@mapNotNull null
            (0 until minOf(row.length(), 8)).map { c -> row.opt(c).let { v -> if (v == null || v == JSONObject.NULL) "" else v.toString() } }
        }
        return if (rows.size >= 1 && rows.any { r -> r.any { it.isNotBlank() } }) rows else null
    }

    /** Slides: [{title, bullets[]}]. A title slide is added automatically; long bullet lists are split over several slides. */
    fun deck(args: JSONObject): DeckSpec? {
        val title = args.optString("title").trim().ifEmpty { "প্রেজেন্টেশন" }
        val arr = args.optJSONArray("slides") ?: return null
        val slides = mutableListOf<Slide>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val t = s.optString("title").trim()
            if (t.isEmpty()) continue
            val b = s.optJSONArray("bullets")
            val bullets = if (b == null) emptyList() else (0 until b.length()).map { b.optString(it).trim().take(200) }.filter { it.isNotEmpty() }
            val chunks = if (bullets.isEmpty()) listOf(emptyList()) else bullets.chunked(MAX_BULLETS)
            chunks.forEachIndexed { k, part -> slides += Slide(if (k == 0) t.take(100) else "${t.take(90)} (${k + 1})", part) }
            if (slides.size >= MAX_SLIDES) break
        }
        if (slides.isEmpty()) return null
        return DeckSpec(title.take(120), args.optString("subtitle").trim().take(150), slides.take(MAX_SLIDES), Palette.normalize(args.optString("theme")))
    }

    fun sheets(args: JSONObject): SheetSpec? {
        val title = args.optString("title").trim().ifEmpty { "স্প্রেডশিট" }
        val arr = args.optJSONArray("sheets") ?: return null
        val sheets = mutableListOf<Sheet>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val rowsJson = s.optJSONArray("rows") ?: continue
            val rows = mutableListOf<List<String>>()
            for (r in 0 until minOf(rowsJson.length(), MAX_ROWS)) {
                val row = rowsJson.optJSONArray(r) ?: continue
                rows += (0 until minOf(row.length(), MAX_COLS)).map { c ->
                    val v = row.opt(c)
                    if (v == null || v == JSONObject.NULL) "" else v.toString()
                }
            }
            if (rows.isNotEmpty()) sheets += Sheet(s.optString("name").ifBlank { "Sheet${sheets.size + 1}" }, rows)
        }
        return if (sheets.isEmpty()) null else SheetSpec(title.take(150), sheets, Palette.normalize(args.optString("theme")))
    }

    /** Keeps letters from any script (Bengali vowel signs are marks, so \p{M} matters), digits and a few safe symbols. */
    fun fileBase(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{M}\\p{N}._ -]"), "").trim().replace(' ', '_').take(60).ifEmpty { "document" }

    const val MAX_ROWS = 2000
    const val MAX_COLS = 50
    const val MAX_BULLETS = 6
    const val MAX_SLIDES = 30
}
