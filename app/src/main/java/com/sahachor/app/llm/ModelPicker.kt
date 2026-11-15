package com.sahachor.app.llm

/** Picks a sensible default from the provider's own live model list, so model names never go stale in the app. */
object ModelPicker {
    private val bad = Regex("embed|imagen|tts|whisper|guard|moderation|vision-preview|image|audio|live|realtime|transcribe|dall|veo|aqa|learnlm", RegexOption.IGNORE_CASE)

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
            ProviderType.ANTHROPIC -> newest(models.filter { "haiku" in it }) ?: newest(models.filter { "sonnet" in it }) ?: models.first()
        }
    }
}
