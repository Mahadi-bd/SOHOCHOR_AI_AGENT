package com.sahachor.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.InetAddress

/** One-tap diagnosis shown on the add-key screen, so a network problem can be pinpointed without guessing. */
object NetCheck {
    private const val HOST = "generativelanguage.googleapis.com"

    suspend fun run(): String = withContext(Dispatchers.IO) {
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
        lines.joinToString("\n")
    }
}
