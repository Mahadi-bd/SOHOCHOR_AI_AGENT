package com.sahachor.app.docs

internal val NUMBER = Regex("^-?(0|[1-9]\\d{0,14})(\\.\\d{1,10})?$")
private val PHONE = Regex("^\\+[0-9 ()-]+$")

/** Files made from web or user text must never contain formulas that call out to the network or other programs. */
object SafeFormula {
    private val banned = listOf("HYPERLINK", "WEBSERVICE", "IMPORTDATA", "IMPORTXML", "IMPORTHTML", "IMPORTFEED", "IMPORTRANGE", "DDE", "CALL(", "EXEC", "REGISTER", "RTD(", "FILTERXML")

    /** strict: also reject quotes and sheet references (used when converting to ODF syntax, which we only support for simple formulas). */
    fun isSafe(formula: String, strict: Boolean = false): Boolean {
        val u = formula.uppercase()
        if (formula.length > 500 || banned.any { it in u } || '|' in formula || '[' in formula) return false
        return !strict || ('!' !in formula && '\'' !in formula && '"' !in formula)
    }
}

/** CSV that opens correctly in Excel and Google Sheets (UTF-8 with BOM, so Bengali text is not garbled). */
object CsvWriter {
    /** Quotes cells as RFC 4180 requires and defuses spreadsheet "formula injection" (cells starting with = + - @). */
    fun cell(value: String): String {
        var s = value
        val risky = s.isNotEmpty() && (s[0] == '=' || s[0] == '@' ||
            (s[0] == '+' && !PHONE.matches(s)) || (s[0] == '-' && !NUMBER.matches(s)))
        if (risky) s = "'$s"
        if (s.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) s = "\"" + s.replace("\"", "\"\"") + "\""
        return s
    }

    fun build(rows: List<List<String>>): ByteArray {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val text = rows.joinToString("\r\n") { r -> r.joinToString(",") { cell(it) } } + "\r\n"
        return bom + text.toByteArray(Charsets.UTF_8)
    }
}
