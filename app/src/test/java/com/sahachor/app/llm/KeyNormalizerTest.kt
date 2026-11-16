package com.sahachor.app.llm

import org.junit.Assert.*
import org.junit.Test

class KeyNormalizerTest {
    private val gem = "AIzaSyA1234567890abcdefghijklmnopqrstuv"

    @Test fun stripsQuotesSpacesAndNewlines() = assertEquals(gem, KeyNormalizer.clean("  \"$gem\"\n"))
    @Test fun stripsBearerPrefix() = assertEquals("gsk_abcdefghijklmnopqrstuvwx", KeyNormalizer.clean("Bearer gsk_abcdefghijklmnopqrstuvwx"))
    @Test fun stripsEnvStyleAssignment() = assertEquals(gem, KeyNormalizer.clean("GEMINI_API_KEY=$gem"))
    @Test fun removesZeroWidthChars() = assertEquals(gem, KeyNormalizer.clean("\u200B$gem\u200B"))
    @Test fun detectsProviders() {
        assertEquals(listOf(ProviderType.GEMINI), KeyNormalizer.candidates(gem))
        assertEquals(listOf(ProviderType.GROQ), KeyNormalizer.candidates("gsk_x"))
        assertEquals(listOf(ProviderType.OPENROUTER), KeyNormalizer.candidates("sk-or-v1-x"))
        assertEquals(listOf(ProviderType.ANTHROPIC), KeyNormalizer.candidates("sk-ant-x"))
        assertEquals(listOf(ProviderType.OPENAI), KeyNormalizer.candidates("sk-proj-x"))
        assertEquals(5, KeyNormalizer.candidates("unknownformatkey").size)
    }
    @Test fun rejectsGarbage() {
        assertFalse(KeyNormalizer.looksLikeKey("short"))
        assertFalse(KeyNormalizer.looksLikeKey("has spaces inside the key value here"))
    }
    @Test fun modelPickerPrefersStableFlash() {
        val m = listOf("gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.0-flash", "text-embedding-004", "gemini-2.5-pro")
        assertEquals("gemini-2.5-flash", ModelPicker.pick(ProviderType.GEMINI, m))
    }
    @Test fun classifiesErrors() {
        assertTrue(Http.classify(401, "") is LlmError.InvalidKey)
        assertTrue(Http.classify(400, "API key not valid") is LlmError.InvalidKey)
        assertTrue(Http.classify(429, "") is LlmError.RateLimit)
        assertTrue(Http.classify(503, "") is LlmError.Server)
    }
}
