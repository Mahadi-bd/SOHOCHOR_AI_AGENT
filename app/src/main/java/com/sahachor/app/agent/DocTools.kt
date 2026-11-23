package com.sahachor.app.agent

import com.sahachor.app.docs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object Formats {
    val all = listOf("pdf", "docx", "odt", "xlsx", "ods", "csv", "pptx")

    /** Reads format or formats (array), lower-cased and de-duplicated. */
    fun requested(args: JSONObject): List<String> {
        val out = linkedSetOf<String>()
        args.optString("format").trim().lowercase().takeIf { it.isNotEmpty() }?.let { out += it }
        args.optJSONArray("formats")?.let { a -> for (i in 0 until a.length()) a.optString(i).trim().lowercase().takeIf { it.isNotEmpty() }?.let { out += it } }
        return out.toList()
    }
}

class CreateDocumentTool(private val store: DocumentStore, private val themeOverride: () -> String? = { null }) : Tool {
    override val name = "create_document"
    override val help = "Create modern, styled files from ONE content. args: format or formats (list of pdf|docx|odt|xlsx|ods|csv|pptx), title, theme (blue|green|purple|orange|teal|red; pick one that suits the topic), register (cholit|sadhu|none: the Bengali style of the whole document, default cholit; never mix), zip (true to also bundle the created files into a ZIP). " +
        "pdf/docx/odt: sections=[{heading, body, bullets:[...], table:[[header,...],[cell,...]]}]. " +
        "xlsx/ods/csv: sheets=[{name, rows:[[header,...],[cell,...]]}] (first row is the header; cells starting with = are formulas; csv uses the sheet named in args.sheet or the first). " +
        "pptx: subtitle, slides=[{title, bullets:[short points, max 6]}] (a title slide is added automatically). " +
        "xlsx/ods/csv files can be opened in Google Sheets from the ডকুমেন্ট tab"
    override val risk = Risk.MEDIUM

    override suspend fun execute(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val formats = Formats.requested(args)
        if (formats.isEmpty()) return@withContext ToolResult(false, "format is missing; use one of ${Formats.all.joinToString()}")
        themeOverride()?.let { args.put("theme", it) } // a colour the user pinned in Settings wins over the model's choice
        val branded = Branding.enabled() // decided by the app (a paid tier later), never by the model
        val logo = store.logo()
        val created = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val register = args.optString("register").trim().lowercase().takeIf { it in setOf("cholit", "sadhu", "none") } ?: "cholit"
        val langNotes = mutableListOf<String>()
        val doc: DocSpec? by lazy {
            SpecParser.doc(args)?.copy(branded = branded)?.let { s ->
                if (register == "none") s else BengaliLang.polishDoc(s, register).let { (p, r) -> r.message(register)?.let { langNotes += it }; p }
            }
        }
        val sheets: SheetSpec? by lazy { SpecParser.sheets(args) }
        val deck: DeckSpec? by lazy {
            SpecParser.deck(args)?.copy(branded = branded)?.let { s ->
                if (register == "none") s else BengaliLang.polishDeck(s, register).let { (p, r) -> r.message(register)?.let { langNotes += it }; p }
            }
        }

        for (fmt in formats) {
            try {
                when (fmt) {
                    "pdf", "docx", "odt" -> {
                        val spec = doc
                        if (spec == null) {
                            problems += "$fmt: missing content, sections must be [{heading, body, bullets, table}]"
                        } else {
                            val f = store.write(fmt, SpecParser.fileBase(spec.title)) { out ->
                                when (fmt) {
                                    "pdf" -> PdfWriter.write(spec, out, logo)
                                    "docx" -> out.write(DocxWriter.build(spec, logo))
                                    else -> out.write(OdtWriter.build(spec, logo))
                                }
                            }
                            created += f.name
                        }
                    }
                    "xlsx", "ods", "csv" -> {
                        val spec = sheets
                        if (spec == null) {
                            problems += "$fmt: missing content, sheets must be [{name, rows:[[...]]}]"
                        } else {
                            val f = store.write(fmt, SpecParser.fileBase(spec.title)) { out ->
                                when (fmt) {
                                    "xlsx" -> out.write(XlsxWriter.build(spec))
                                    "ods" -> out.write(OdsWriter.build(spec))
                                    else -> {
                                        val want = args.optString("sheet").trim()
                                        val sheet = spec.sheets.firstOrNull { it.name.equals(want, ignoreCase = true) } ?: spec.sheets.first()
                                        out.write(CsvWriter.build(sheet.rows))
                                    }
                                }
                            }
                            created += f.name
                        }
                    }
                    "pptx" -> {
                        val spec = deck
                        if (spec == null) {
                            problems += "pptx: missing content, slides must be [{title, bullets:[...]}]"
                        } else {
                            val f = store.write("pptx", SpecParser.fileBase(spec.title)) { out -> out.write(PptxWriter.build(spec, logo)) }
                            created += f.name
                        }
                    }
                    else -> problems += "$fmt: unsupported, use ${Formats.all.joinToString()}"
                }
            } catch (e: Exception) {
                problems += "$fmt: failed (${e.javaClass.simpleName})"
            }
        }
        if (args.optBoolean("zip") && created.isNotEmpty()) {
            val base = SpecParser.fileBase(args.optString("title").ifBlank { "LIFE_EASY" }) + "_files"
            store.zip(base, created.toList())?.let { created += it.name }
        }
        if (created.isEmpty()) return@withContext ToolResult(false, problems.joinToString("; "))
        val lang = if (langNotes.isEmpty()) "" else " ${langNotes.distinct().joinToString(" ")}"
        val tail = (if (problems.isEmpty()) "" else " Problems: ${problems.joinToString("; ")}.") + lang
        ToolResult(true, "created ${created.joinToString()}.$tail Tell the user the files are in the ডকুমেন্ট tab, where they can open or share them.")
    }
}

class ZipDocumentsTool(private val store: DocumentStore) : Tool {
    override val name = "zip_documents"
    override val help = "Bundle files from the ডকুমেন্ট tab into one ZIP. args: name (optional), files (optional list of file names; default: all files)"
    override val risk = Risk.MEDIUM

    override suspend fun execute(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val arr: JSONArray? = args.optJSONArray("files")
        val names = arr?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }?.takeIf { it.isNotEmpty() }
        val base = SpecParser.fileBase(args.optString("name").ifBlank { "LIFE_EASY_files" })
        val f = store.zip(base, names) ?: return@withContext ToolResult(false, "no matching files to bundle (or they are larger than 50 MB)")
        ToolResult(true, "created ${f.name}. Tell the user it is in the ডকুমেন্ট tab.")
    }
}
