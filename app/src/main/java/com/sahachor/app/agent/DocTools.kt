package com.sahachor.app.agent

import com.sahachor.app.docs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CreateDocumentTool(private val store: DocumentStore) : Tool {
    override val name = "create_document"
    override val help = "Create a styled file. args: format (pdf|docx|xlsx|pptx), title, theme (blue|green|purple|orange|teal|red; pick one that suits the topic). " +
        "pdf/docx: sections=[{heading, body, bullets:[...], table:[[header,...],[cell,...]]}]. " +
        "xlsx: sheets=[{name, rows:[[header,...],[cell,...]]}] (first row is the header; cells starting with = are formulas). " +
        "pptx: subtitle, slides=[{title, bullets:[short points, max 6]}] (a title slide is added automatically)"
    override val risk = Risk.MEDIUM

    override suspend fun execute(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val done = "Tell the user it is in the ডকুমেন্ট tab, where they can open or share it."
        when (val fmt = args.optString("format").trim().lowercase()) {
            "pdf", "docx" -> {
                val spec = SpecParser.doc(args) ?: return@withContext ToolResult(false, "missing content: sections must be [{heading, body, bullets, table}]")
                val f = store.write(fmt, SpecParser.fileBase(spec.title)) { out ->
                    if (fmt == "pdf") PdfWriter.write(spec, out) else out.write(DocxWriter.build(spec))
                }
                ToolResult(true, "created ${f.name}. $done")
            }
            "xlsx" -> {
                val spec = SpecParser.sheets(args) ?: return@withContext ToolResult(false, "missing content: sheets must be [{name, rows:[[...]]}]")
                val f = store.write("xlsx", SpecParser.fileBase(spec.title)) { out -> out.write(XlsxWriter.build(spec)) }
                ToolResult(true, "created ${f.name}. $done")
            }
            "pptx" -> {
                val spec = SpecParser.deck(args) ?: return@withContext ToolResult(false, "missing content: slides must be [{title, bullets:[...]}]")
                val f = store.write("pptx", SpecParser.fileBase(spec.title)) { out -> out.write(PptxWriter.build(spec)) }
                ToolResult(true, "created ${f.name} with ${spec.slides.size + 1} slides. $done")
            }
            else -> ToolResult(false, "format must be pdf, docx, xlsx or pptx")
        }
    }
}
