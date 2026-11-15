package com.sahachor.app.agent

import org.json.JSONException
import org.json.JSONObject
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

data class ToolCall(val tool: String, val args: JSONObject)

/** Providers differ on tool-calling APIs, so the agent uses one portable protocol: the model replies with a JSON object. */
object ToolCallParser {
    fun parse(text: String): ToolCall? {
        var i = text.indexOf('{')
        while (i >= 0) {
            val end = matchEnd(text, i)
            if (end > i) {
                try {
                    val o = JSONObject(text.substring(i, end + 1))
                    val t = o.optString("tool")
                    if (t.isNotBlank()) return ToolCall(t.trim(), o.optJSONObject("args") ?: JSONObject())
                } catch (e: JSONException) { /* not our JSON, keep scanning */ }
            }
            i = text.indexOf('{', i + 1)
        }
        return null
    }

    private fun matchEnd(s: String, start: Int): Int {
        var depth = 0; var inStr = false; var esc = false
        for (k in start until s.length) {
            val c = s[k]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return k } }
        }
        return -1
    }
}

object TimeParse {
    private val withOffset = Regex(".*(Z|[+-]\\d\\d:\\d\\d)$")
    private val noSeconds = Regex("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d$")

    /** Accepts "2026-11-16T09:00", "2026-11-16 09:00" or with an offset. Returns epoch millis or null. */
    fun parse(raw: String, zone: ZoneId): Long? {
        val s = raw.trim().replace(' ', 'T')
        return try {
            if (withOffset.matches(s)) OffsetDateTime.parse(s).toInstant().toEpochMilli()
            else LocalDateTime.parse(if (noSeconds.matches(s)) "$s:00" else s).atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeException) { null }
    }
}
