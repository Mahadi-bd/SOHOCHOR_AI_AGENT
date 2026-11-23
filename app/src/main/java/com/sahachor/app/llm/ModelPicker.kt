package com.sahachor.app.llm

/** Picks a sensible default from the provider's own live model list, so model names never go stale in the app. */
object ModelPicker {
    private val bad = Regex("embed|imagen|tts|whisper|guard|moderation|vision-preview|image|audio|live|realtime|transcribe|dall|veo|aqa|learnlm", RegexOption.IGNORE_CASE)

    /** Well-known model names to try when the provider's model list cannot be fetched. */
    fun fallbacks(type: ProviderType): List<String> = when (type) {
        ProviderType.GEMINI -> listOf("gemini-flash-latest", "gemini-flash-lite-latest")
        ProviderType.GROQ -> listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
        ProviderType.OPENAI -> listOf("gpt-4o-mini")
        ProviderType.ANTHROPIC -> listOf("claude-haiku-5-5")
        ProviderType.OPENROUTER -> emptyList()
        ProviderType.DEEPSEEK -> listOf("deepseek-chat")
        ProviderType.GITHUB -> listOf("openai/gpt-4o-mini", "openai/gpt-4.1-mini")
    }

    private val unstable = Regex("preview|exp|thinking", RegexOption.IGNORE_CASE)

    /** Fast, cheap models first (they answer soonest), then the rest; stable before preview, newer before older. */
    fun rank(type: ProviderType, all: List<String>): List<String> {
        fun tier(m: String) = if (type != ProviderType.GEMINI) 0 else when {
            "flash-lite" in m -> 0
            "flash" in m -> 1
            "gemma" in m -> 2
            "pro" in m -> 4
            else -> 3
        }
        return all.filterNot { bad.containsMatchIn(it) }
            .sortedWith(compareBy<String>({ tier(it) }, { unstable.containsMatchIn(it) }).thenByDescending { it })
    }

    /** Models to try, best guess first. */
    fun candidates(type: ProviderType, all: List<String>): List<String> =
        if (type == ProviderType.GEMINI) rank(type, all) else (listOfNotNull(pick(type, all)) + rank(type, all)).distinct()

    fun pick(type: ProviderType, all: List<String>): String? {
        val models = all.filterNot { bad.containsMatchIn(it) }
        if (models.isEmpty()) return null
        fun newest(list: List<String>) = list.sortedDescending().firstOrNull()
        return when (type) {
            ProviderType.GEMINI -> newest(models.filter { Regex("^gemini-[\\d.]+-flash$").matches(it) }) // stable flash, not lite/preview
                ?: newest(models.filter { "flash" in it && "preview" !in it && "exp" !in it }) ?: models.first()
            ProviderType.GROQ -> models.firstOrNull { it == "llama-3.3-70b-versatile" } ?: models.firstOrNull { it == "llama-3.1-8b-instant" } ?: models.first()
            ProviderType.OPENROUTER -> models.firstOrNull { it.endsWith(":free") } ?: models.first()
            ProviderType.OPENAI -> models.firstOrNull { it == "gpt-4o-mini" } ?: newest(models.filter { it.startsWith("gpt-") }) ?: models.first()
            ProviderType.DEEPSEEK -> models.firstOrNull { it == "deepseek-chat" } ?: models.first()
            ProviderType.GITHUB -> models.firstOrNull { it == "openai/gpt-4o-mini" } ?: models.firstOrNull { it.startsWith("openai/") && "mini" in it } ?: models.first()
            ProviderType.ANTHROPIC -> newest(models.filter { "haiku" in it }) ?: newest(models.filter { "sonnet" in it }) ?: models.first()
        }
    }
}
