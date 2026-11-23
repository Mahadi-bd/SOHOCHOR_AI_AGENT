package com.sahachor.app.llm

/** Cleans what real users paste (quotes, spaces, "Bearer ", line breaks) and guesses the provider from the key shape. */
object KeyNormalizer {
    private val invisible = Regex("[\\s\\u200B-\\u200D\\uFEFF\\u00A0]")

    fun clean(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^(?i)(authorization:\\s*)?bearer\\s+"), "")
        s = s.replace(Regex("^(?i)[a-z_]*api[_-]?key\\s*[=:]\\s*"), "")
        s = s.replace(invisible, "")
        s = s.trim('"', '\'', '`', '“', '”', '‘', '’', ',', ';')
        return s
    }

    fun looksLikeKey(key: String) = key.length in 20..300 && key.all { it.isLetterOrDigit() || it in "-_." }

    /** Returns the most likely provider first; unknown shapes return all providers to try in order. */
    fun candidates(key: String): List<ProviderType> = when {
        key.startsWith("AIza") -> listOf(ProviderType.GEMINI)
        key.startsWith("gsk_") -> listOf(ProviderType.GROQ)
        key.startsWith("sk-or-") -> listOf(ProviderType.OPENROUTER)
        key.startsWith("sk-ant-") -> listOf(ProviderType.ANTHROPIC)
        key.startsWith("ghp_") || key.startsWith("github_pat_") || key.startsWith("gho_") || key.startsWith("ghu_") -> listOf(ProviderType.GITHUB)
        key.startsWith("sk-proj-") -> listOf(ProviderType.OPENAI)
        key.startsWith("sk-") -> if (key.length <= 40) listOf(ProviderType.DEEPSEEK, ProviderType.OPENAI) else listOf(ProviderType.OPENAI, ProviderType.DEEPSEEK) // DeepSeek keys are short (sk- + 32 hex)
        else -> ProviderType.values().toList()
    }
}
