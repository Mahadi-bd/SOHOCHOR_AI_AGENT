package com.sahachor.app.agent

import com.sahachor.app.docs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CreateDocumentTool(private val store: DocumentStore) : Tool {
    override val name = "create_document"
    override val help = "Create a file. args: format (pdf|docx|xlsx), title. " +
        "For pdf/docx: sections=[{heading, body, bullets:[...]}]. " +
        "For xlsx: sheets=[{name, rows:[[cell,...],...]}] (cells starting with = are formulas)"
    override val risk = Risk.MEDIUM

    override suspend fun execute(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        when (val fmt = args.optString("format").trim().lowercase()) {
            "pdf", "docx" -> {
                val spec = SpecParser.doc(args) ?: return@withContext ToolResult(false, "missing content: sections must be [{heading, body, bullets}]")
                val f = store.write(fmt, SpecParser.fileBase(spec.title)) { out ->
                    if (fmt == "pdf") PdfWriter.write(spec, out) else out.write(DocxWriter.build(spec))
                }
                ToolResult(true, "created ${f.name}. Tell the user it is in the ডকুমেন্ট tab, where they can open or share it.")
            }
            "xlsx" -> {
                val spec = SpecParser.sheets(args) ?: return@withContext ToolResult(false, "missing content: sheets must be [{name, rows:[[...]]}]")
                val f = store.write("xlsx", SpecParser.fileBase(spec.title)) { out -> out.write(XlsxWriter.build(spec)) }
                ToolResult(true, "created ${f.name}. Tell the user it is in the ডকুমেন্ট tab, where they can open or share it.")
            }
            else -> ToolResult(false, "format must be pdf, docx or xlsx")
        }
    }
}
