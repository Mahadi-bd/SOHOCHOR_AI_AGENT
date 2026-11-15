package com.sahachor.app.docs

import org.json.JSONObject

sealed class Block {
    data class Heading(val text: String) : Block()
    data class Para(val text: String) : Block()
    data class Bullets(val items: List<String>) : Block()
}

data class DocSpec(val title: String, val blocks: List<Block>)
data class Sheet(val name: String, val rows: List<List<String>>)
data class SheetSpec(val title: String, val sheets: List<Sheet>)

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
        }
        return if (blocks.isEmpty()) null else DocSpec(title.take(150), blocks)
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
        return if (sheets.isEmpty()) null else SheetSpec(title.take(150), sheets)
    }

    /** Keeps letters from any script (Bengali vowel signs are marks, so \p{M} matters), digits and a few safe symbols. */
    fun fileBase(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{M}\\p{N}._ -]"), "").trim().replace(' ', '_').take(60).ifEmpty { "document" }

    const val MAX_ROWS = 2000
    const val MAX_COLS = 50
}
