package com.sahachor.app.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ResilienceTest {
    private val key = "AIzaSyA1234567890abcdefghijklmnopqrstuv"

    private class Fake(val models: List<String>, val behaviour: suspend (String) -> String) : LlmClient {
        override val type = ProviderType.GEMINI
        override suspend fun listModels(key: String) = models
        override suspend fun chat(key: String, model: String, system: String?, messages: List<ChatMsg>) = behaviour(model)
    }

    @Test fun rankPutsFastModelsFirstAndDropsNonChatModels() {
        val r = ModelPicker.rank(ProviderType.GEMINI, listOf("gemini-9-pro", "gemini-9-flash", "gemini-9-flash-lite", "gemma-9", "gemini-9-flash-preview", "text-embedding-1"))
        assertEquals(listOf("gemini-9-flash-lite", "gemini-9-flash", "gemini-9-flash-preview", "gemma-9", "gemini-9-pro"), r)
    }

    @Test fun slowModelDoesNotBlockSetup() = runBlocking {
        val c = Fake(listOf("gemini-9-flash-slow", "gemini-9-flash-lite")) { m -> if ("slow" in m) { delay(60_000); "late" } else "ok" }
        val t0 = System.currentTimeMillis()
        val r = KeySetup { c }.connect(key, ProviderType.GEMINI)
        assertTrue(System.currentTimeMillis() - t0 < 10_000)
        r as SetupResult.Success
        assertEquals("gemini-9-flash-lite", r.config.model)
        assertNull(r.note)
        assertTrue("gemini-9-flash-slow" in r.config.alt)
    }

    @Test fun verifiedKeyIsSavedEvenIfNoModelAnswers() = runBlocking {
        val c = Fake(listOf("gemini-9-flash")) { throw LlmException(LlmError.Offline("TIMEOUT")) }
        val r = KeySetup { c }.connect(key, ProviderType.GEMINI) as SetupResult.Success
        assertEquals("gemini-9-flash", r.config.model)
        assertNotNull(r.note)
    }

    @Test fun invalidKeyIsReported() = runBlocking {
        val c = Fake(listOf("gemini-9-flash")) { throw LlmException(LlmError.InvalidKey) }
        val r = KeySetup { c }.connect(key, ProviderType.GEMINI)
        assertTrue(r is SetupResult.Failure && r.error is LlmError.InvalidKey)
    }

    @Test fun fallbackSkipsSlowModelAndReturnsTheWorkingOne() = runBlocking {
        val o = ModelFallback.run(listOf("a", "b")) { m -> if (m == "a") throw LlmException(LlmError.Offline("TIMEOUT")) else "hi" }
        assertEquals(KeyOutcome.Ok("hi", "b"), o)
    }

    @Test fun fallbackStopsOnInvalidKeyAndOnDeadNetwork() = runBlocking {
        var calls = 0
        val bad = ModelFallback.run(listOf("a", "b")) { calls++; throw LlmException(LlmError.InvalidKey) } as KeyOutcome.Failed
        assertTrue(bad.invalid); assertEquals(1, calls)
        calls = 0
        val down = ModelFallback.run(listOf("a", "b")) { calls++; throw LlmException(LlmError.Offline("DNS")) } as KeyOutcome.Failed
        assertTrue(down.networkDown); assertEquals(1, calls)
    }

    @Test fun fallbackTriesNextModelWhenOneIsMissingOrRateLimited() = runBlocking {
        val o = ModelFallback.run(listOf("gone", "busy", "ok")) { m -> when (m) { "gone" -> throw LlmException(LlmError.NoModel); "busy" -> throw LlmException(LlmError.RateLimit); else -> "fine" } }
        assertEquals(KeyOutcome.Ok("fine", "ok"), o)
        val none = ModelFallback.run(listOf("x")) { throw LlmException(LlmError.NoModel) } as KeyOutcome.Failed
        assertEquals(LlmError.NoModel, none.error)
    }

    @Test fun http404MeansModelMissing() = assertTrue(Http.classify(404, "") is LlmError.NoModel)
}
