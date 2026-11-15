package com.sahachor.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.InetAddress

/** One-tap diagnosis shown on the add-key screen, so a network problem can be pinpointed without guessing. */
object NetCheck {
    private const val HOST = "generativelanguage.googleapis.com"

    suspend fun run(rawKey: String = "", type: ProviderType = ProviderType.GEMINI): String = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        lines += try {
            "সিস্টেম DNS: ✅ " + InetAddress.getAllByName(HOST).joinToString { it.hostAddress ?: "?" }
        } catch (e: Exception) { "সিস্টেম DNS: ❌ ${e.javaClass.simpleName}" }
        lines += try {
            val r = Http.dohLookup(HOST)
            if (r == null) "বিকল্প DNS: ❌ উত্তর পাওয়া যায়নি" else "বিকল্প DNS: ✅ " + r.joinToString { it.hostAddress ?: "?" }
        } catch (e: Exception) { "বিকল্প DNS: ❌ ${e.javaClass.simpleName}" }
        lines += try {
            Http.client.newCall(Request.Builder().url("https://$HOST/").build()).execute().use { "HTTPS সংযোগ: ✅ (HTTP ${it.code}; ৪০৪ আসা স্বাভাবিক)" }
        } catch (e: Exception) { "HTTPS সংযোগ: ❌ ${e.javaClass.simpleName}: ${e.message.orEmpty().take(100)}" }
        val key = KeyNormalizer.clean(rawKey)
        if (key.isNotEmpty()) {
            lines += "কী: ${key.length} অক্ষর, শুরু ${key.take(4)}…, " + if (KeyNormalizer.looksLikeKey(key)) "ফরম্যাট ঠিক" else "ফরম্যাট সন্দেহজনক"
            val c = clientFor(type)
            lines += try { "মডেল তালিকা: ✅ ${c.listModels(key).size}টি" }
            catch (e: LlmException) { "মডেল তালিকা: ❌ ${e.error.message} ${e.error.fix}" }
            catch (e: Exception) { "মডেল তালিকা: ❌ ${e.javaClass.simpleName}" }
            val m = ModelPicker.fallbacks(type).firstOrNull() ?: "?"
            lines += try { c.chat(key, m, null, listOf(ChatMsg("user", "Reply with: ok"))); "টেস্ট বার্তা ($m): ✅" }
            catch (e: LlmException) { "টেস্ট বার্তা ($m): ❌ ${e.error.message} ${e.error.fix}" }
            catch (e: Exception) { "টেস্ট বার্তা ($m): ❌ ${e.javaClass.simpleName}" }
        } else lines += "API কী পেস্ট করে আবার চাপলে কী-ও পরীক্ষা হবে।"
        lines.joinToString("\n")
    }
}
