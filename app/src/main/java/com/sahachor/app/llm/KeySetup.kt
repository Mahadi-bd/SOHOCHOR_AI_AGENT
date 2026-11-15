package com.sahachor.app.llm

import kotlinx.coroutines.CancellationException

sealed class SetupResult {
    data class Success(val config: ProviderConfig) : SetupResult()
    data class Failure(val error: LlmError, val triedKeyShapeOk: Boolean) : SetupResult()
}

/** One paste box is enough: clean the input, detect the provider, verify with a free call, then a 1-token chat test. */
class KeySetup(private val clients: (ProviderType) -> LlmClient = ::clientFor) {

    suspend fun connect(raw: String, forced: ProviderType? = null): SetupResult {
        val key = KeyNormalizer.clean(raw)
        if (!KeyNormalizer.looksLikeKey(key)) return SetupResult.Failure(LlmError.InvalidKey, false)
        val order = if (forced != null) listOf(forced) else KeyNormalizer.candidates(key)
        var firstError: LlmError? = null
        for (type in order) {
            try {
                val c = clients(type)
                val listed = try { c.listModels(key) } catch (e: LlmException) {
                    if (e.error is LlmError.InvalidKey) throw e
                    emptyList() // listing failed (e.g. a large response was cut by the network): fall back to well-known names
                }
                val candidates = (listOfNotNull(ModelPicker.pick(type, listed)) + ModelPicker.fallbacks(type)).distinct()
                var last: LlmException? = null
                for (m in candidates) {
                    try {
                        c.chat(key, m, null, listOf(ChatMsg("user", "Reply with: ok")))
                        return SetupResult.Success(ProviderConfig(type, key, m))
                    } catch (e: LlmException) {
                        last = e
                        if (e.error is LlmError.InvalidKey || e.error is LlmError.RateLimit) break
                    }
                }
                throw last ?: LlmException(LlmError.NoModel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                // A guessed provider saying "invalid key" is expected; a more specific error from another one is more useful.
                if (firstError == null || firstError is LlmError.InvalidKey) firstError = e.error
                if (e.error is LlmError.Offline) break
            } catch (e: Exception) {
                firstError = firstError ?: LlmError.Unknown(e.javaClass.simpleName)
            }
        }
        return SetupResult.Failure(firstError ?: LlmError.InvalidKey, true)
    }
}
