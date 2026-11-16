package com.sahachor.app.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

sealed class SetupResult {
    /** note: set when the key is verified but no model answered the test message (e.g. all busy). */
    data class Success(val config: ProviderConfig, val note: String? = null) : SetupResult()
    data class Failure(val error: LlmError, val triedKeyShapeOk: Boolean) : SetupResult()
}

/** One paste box is enough: clean the input, detect the provider, verify the key, find a model that answers. Never waits minutes. */
class KeySetup(private val clients: (ProviderType) -> LlmClient = ::clientFor) {

    suspend fun connect(raw: String, forced: ProviderType? = null): SetupResult {
        val key = KeyNormalizer.clean(raw)
        if (!KeyNormalizer.looksLikeKey(key)) return SetupResult.Failure(LlmError.InvalidKey, false)
        val order = if (forced != null) listOf(forced) else KeyNormalizer.candidates(key)
        var firstError: LlmError? = null
        for (type in order) {
            try {
                return verify(type, key)
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

    /** The free model-list call proves the key; then several models are tried at once and the first to answer is used. */
    private suspend fun verify(type: ProviderType, key: String): SetupResult.Success {
        val c = clients(type)
        val listed: List<String>? = try {
            withContext(FastFail()) { c.listModels(key) }
        } catch (e: LlmException) {
            if (e.error is LlmError.InvalidKey) throw e
            null // could not list; fall back to well-known model names
        }
        val ranked = (if (listed != null) ModelPicker.candidates(type, listed) else ModelPicker.fallbacks(type))
            .ifEmpty { listed.orEmpty().take(3) }
        if (ranked.isEmpty()) throw LlmException(LlmError.NoModel)

        val race = ModelProbe.race(c, key, ranked.take(6))
        race.winner?.let { w -> return SetupResult.Success(ProviderConfig(type, key, w, alt = ranked.filter { it != w }.take(2))) }
        race.results.firstOrNull { it.error is LlmError.InvalidKey }?.error?.let { throw LlmException(it) }

        val err = race.results.firstNotNullOfOrNull { it.error } ?: LlmError.NoModel
        if (listed != null) {
            val note = "কী যাচাই হয়েছে, কিন্তু টেস্ট বার্তায় কোনো মডেল সাড়া দেয়নি: ${err.message} ${err.fix}"
            return SetupResult.Success(ProviderConfig(type, key, ranked.first(), alt = ranked.drop(1).take(2)), note)
        }
        throw LlmException(err)
    }
}
