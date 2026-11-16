package com.sahachor.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.InetAddress

/** One-tap diagnosis shown on the add-key screen, so a problem can be pinpointed without guessing. */
object NetCheck {
    private const val HOST = "generativelanguage.googleapis.com"

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
        val listed: List<String>? = try { withContext(FastFail()) { c.listModels(key) } } catch (e: Exception) { null }
        lines += if (listed != null) "মডেল তালিকা: ✅ ${listed.size}টি" else "মডেল তালিকা: ❌"
        val ranked = if (listed != null) ModelPicker.candidates(type, listed) else ModelPicker.fallbacks(type)
        for (r in ModelProbe.probeAll(c, key, ranked.take(6))) {
            val why = r.error?.let { it.message + if (it is LlmError.Offline) " [${it.reason}]" else "" }.orEmpty()
            lines += "মডেল ${r.model}: " + if (r.ok) "✅ ${r.ms}ms" else "❌ ${r.ms}ms $why"
        }
        lines.joinToString("\n")
    }
}
