package com.sahachor.app.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

sealed class SetupResult {
    /** note: set when the key is verified but the follow-up test message failed (e.g. a slow network). */
    data class Success(val config: ProviderConfig, val note: String? = null) : SetupResult()
    data class Failure(val error: LlmError, val triedKeyShapeOk: Boolean) : SetupResult()
}

/** One paste box is enough: clean the input, detect the provider, verify the key, pick a model. Never waits minutes. */
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

    /** The free model-list call proves the key. The test message is a bonus: if only it fails, the key is still saved with a note. */
    private suspend fun verify(type: ProviderType, key: String): SetupResult.Success {
        val c = clients(type)
        val listed: List<String>? = try {
            withContext(FastFail()) { c.listModels(key) }
        } catch (e: LlmException) {
            if (e.error is LlmError.InvalidKey) throw e
            null // could not list; fall back to well-known model names
        }
        val candidates = (listOfNotNull(listed?.let { ModelPicker.pick(type, it) }) + ModelPicker.fallbacks(type)).distinct()
        val model0 = candidates.firstOrNull() ?: listed?.firstOrNull() ?: throw LlmException(LlmError.NoModel)
        var lastErr: LlmException? = null
        for (m in candidates.ifEmpty { listOf(model0) }.take(2)) {
            try {
                withContext(FastFail()) { c.chat(key, m, null, listOf(ChatMsg("user", "Reply with: ok"))) }
                return SetupResult.Success(ProviderConfig(type, key, m))
            } catch (e: LlmException) {
                lastErr = e
                if (e.error is LlmError.InvalidKey) throw e
                if (e.error is LlmError.RateLimit) break
            }
        }
        val err = lastErr ?: LlmException(LlmError.NoModel)
        if (listed != null) {
            return SetupResult.Success(ProviderConfig(type, key, model0), "কী যাচাই হয়েছে, কিন্তু টেস্ট বার্তা যায়নি: ${err.error.message} ${err.error.fix}")
        }
        throw err
    }
}
