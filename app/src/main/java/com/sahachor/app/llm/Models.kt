package com.sahachor.app.llm

enum class ProviderType(
    val label: String,
    val baseUrl: String,
    val keyPageUrl: String,
    val note: String,
) {
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "https://aistudio.google.com/apikey", "ফ্রি টিয়ার আছে · সবচেয়ে সহজ"),
    GROQ("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", "ফ্রি টিয়ার আছে · খুব দ্রুত"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys", "কিছু মডেল ফ্রি"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys", "পেইড"),
    ANTHROPIC("Claude (Anthropic)", "https://api.anthropic.com/v1", "https://console.anthropic.com/settings/keys", "পেইড"),
}

data class ChatMsg(val role: String, val content: String) // role: "user" | "assistant"

/** alt: backup models for the same key, tried in order when the main one is slow, missing or overloaded. */
data class ProviderConfig(val type: ProviderType, val apiKey: String, val model: String, val valid: Boolean = true, val alt: List<String> = emptyList())

/** Every failure is turned into plain Bengali with a concrete next step. The app never shows raw HTTP errors. */
sealed class LlmError(val message: String, val fix: String) {
    object InvalidKey : LlmError("কী-টি কাজ করছে না।", "সম্পূর্ণ কী কপি করেছেন কিনা দেখুন, অথবা নতুন একটি কী তৈরি করুন।")
    object RateLimit : LlmError("আজকের ফ্রি সীমা শেষ বা অনেক দ্রুত অনুরোধ হয়েছে।", "কিছুক্ষণ পরে চেষ্টা করুন, অথবা আরেকটি প্রোভাইডারের কী যোগ করুন।")
    object NoCredit : LlmError("এই অ্যাকাউন্টে ব্যালেন্স বা কোটা নেই।", "প্রোভাইডারের সাইটে বিলিং দেখুন, অথবা ফ্রি প্রোভাইডার (Gemini/Groq) ব্যবহার করুন।")
    /** reason is a short code (DNS, TIMEOUT, SSL, IO:<name>) shown to the user so a network problem can be diagnosed. */
    class Offline(val reason: String) : LlmError("প্রোভাইডারের সার্ভারে পৌঁছানো যাচ্ছে না।", offlineFix(reason) + " [কোড: $reason]")
    object Region : LlmError("এই প্রোভাইডার আপনার অঞ্চল থেকে সীমিত।", "VPN চালু করে চেষ্টা করুন, অথবা Groq/OpenRouter-এর কী ব্যবহার করুন।")
    object Server : LlmError("প্রোভাইডারের সার্ভারে সমস্যা চলছে।", "একটু পরে আবার চেষ্টা করুন।")
    object NoModel : LlmError("এই কী দিয়ে ব্যবহারযোগ্য মডেল পাওয়া যায়নি।", "অন্য প্রোভাইডার চেষ্টা করুন।")
    object NoProvider : LlmError("এখনো কোনো AI যুক্ত করা হয়নি।", "সেটিংস থেকে একটি API কী যোগ করুন।")
    class Unknown(detail: String) : LlmError("অপ্রত্যাশিত সমস্যা হয়েছে।", "আবার চেষ্টা করুন। ($detail)")
}

class LlmException(val error: LlmError) : Exception(error.message)

private fun offlineFix(reason: String): String = when {
    reason == "DNS" -> "ফোন সার্ভারের ঠিকানা খুঁজে পাচ্ছে না। Settings → Network → Private DNS বন্ধ বা Automatic করুন, VPN থাকলে বন্ধ করুন, অথবা Wi-Fi ও মোবাইল ডাটা বদলে চেষ্টা করুন।"
    reason == "TIMEOUT" -> "সংযোগ খুব ধীর বা কোথাও আটকে যাচ্ছে। অন্য নেটওয়ার্কে বা VPN দিয়ে চেষ্টা করুন।"
    reason == "SSL" -> "নিরাপদ সংযোগ তৈরি হয়নি। ফোনের তারিখ-সময় Automatic আছে কিনা দেখুন, অথবা অন্য নেটওয়ার্কে চেষ্টা করুন।"
    else -> "নেট চালু আছে কিনা দেখে আবার চেষ্টা করুন; না হলে অন্য নেটওয়ার্কে চেষ্টা করুন।"
}
