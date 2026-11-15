package com.sahachor.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.InetAddress

/** One-tap diagnosis shown on the add-key screen, so a network problem can be pinpointed without guessing. */
object NetCheck {
    private const val HOST = "generativelanguage.googleapis.com"

    private suspend fun timed(label: String, block: suspend () -> Unit): String {
        val t0 = System.currentTimeMillis()
        return try {
            block(); "$label: ✅ ${System.currentTimeMillis() - t0}ms"
        } catch (e: LlmException) {
            "$label: ❌ ${System.currentTimeMillis() - t0}ms ${e.error.message} ${e.error.fix}"
        } catch (e: Exception) { "$label: ❌ ${e.javaClass.simpleName}" }
    }

    suspend fun run(rawKey: String = "", type: ProviderType = ProviderType.GEMINI): String = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        lines += try {
            "সিস্টেম DNS: ✅ " + InetAddress.getAllByName(HOST).joinToString { it.hostAddress ?: "?" }
        } catch (e: Exception) { "সিস্টেম DNS: ❌ ${e.javaClass.simpleName}" }
        lines += try {
            Http.client.newCall(Request.Builder().url("https://$HOST/").build()).execute().use { "HTTPS সংযোগ: ✅ (HTTP ${it.code}; ৪০৪ আসা স্বাভাবিক)" }
        } catch (e: Exception) { "HTTPS সংযোগ: ❌ ${e.javaClass.simpleName}: ${e.message.orEmpty().take(100)}" }

        val key = KeyNormalizer.clean(rawKey)
        if (key.isEmpty()) return@withContext (lines + "API কী পেস্ট করে আবার চাপলে কী-ও পরীক্ষা হবে।").joinToString("\n")
        lines += "কী: ${key.length} অক্ষর, শুরু ${key.take(4)}…, " + if (KeyNormalizer.looksLikeKey(key)) "ফরম্যাট ঠিক" else "ফরম্যাট সন্দেহজনক"

        val c = clientFor(type)
        var listed: List<String> = emptyList()
        lines += timed("মডেল তালিকা (GET)") { listed = withContext(FastFail()) { c.listModels(key) } }
        if (type == ProviderType.GEMINI) {
            val body = """{"contents":[{"parts":[{"text":"hi"}]}]}"""
            val url = "${type.baseUrl}/models/gemini-2.5-flash:countTokens"
            val h = mapOf("x-goog-api-key" to key)
            lines += timed("POST ছোট অনুরোধ (HTTP/1.1)") { withContext(FastFail()) { Http.execute(url, h, body) } }
            lines += timed("POST ছোট অনুরোধ (HTTP/2)") { withContext(FastFail()) { Http.execute(url, h, body, Http.quickH2) } }
        }
        val models = (listOfNotNull(ModelPicker.pick(type, listed)) + ModelPicker.fallbacks(type)).distinct().take(3)
        for (m in models) {
            lines += timed("টেস্ট বার্তা $m") { withContext(FastFail()) { c.chat(key, m, null, listOf(ChatMsg("user", "Reply with: ok"))) } }
        }
        lines.joinToString("\n")
    }
}
