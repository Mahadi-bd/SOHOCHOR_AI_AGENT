package com.sahachor.app.llm

import com.sahachor.app.core.SecretStore
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

fun interface Chatter { suspend fun chat(system: String?, messages: List<ChatMsg>): Result<String> }

/** Holds the user's providers (encrypted at rest) and falls back to the next one when a provider is rate-limited or down. */
class LlmRouter(private val store: SecretStore, private val clients: (ProviderType) -> LlmClient = ::clientFor) : Chatter {
    @Volatile private var configs: List<ProviderConfig> = load()

    fun providers(): List<ProviderConfig> = configs
    fun hasProvider(): Boolean = configs.any { it.valid }

    @Synchronized
    fun add(cfg: ProviderConfig) {
        configs = listOf(cfg) + configs.filterNot { it.type == cfg.type } // newest first
        save()
    }

    @Synchronized
    fun remove(type: ProviderType) { configs = configs.filterNot { it.type == type }; save() }

    override suspend fun chat(system: String?, messages: List<ChatMsg>): Result<String> {
        val active = configs.filter { it.valid }
        if (active.isEmpty()) return Result.failure(LlmException(LlmError.NoProvider))
        var last: LlmError = LlmError.NoProvider
        for (cfg in active) {
            try {
                return Result.success(clients(cfg.type).chat(cfg.apiKey, cfg.model, system, messages))
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                last = e.error
                if (e.error is LlmError.InvalidKey) markInvalid(cfg.type)
                if (e.error is LlmError.Offline) break // other cloud providers would fail the same way
            } catch (e: Exception) {
                last = LlmError.Unknown(e.javaClass.simpleName)
            }
        }
        return Result.failure(LlmException(last))
    }

    @Synchronized
    private fun markInvalid(type: ProviderType) {
        configs = configs.map { if (it.type == type) it.copy(valid = false) else it }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        configs.forEach { arr.put(JSONObject().put("t", it.type.name).put("k", it.apiKey).put("m", it.model).put("v", it.valid)) }
        store.put(KEY, arr.toString())
    }

    private fun load(): List<ProviderConfig> = try {
        val arr = JSONArray(store.get(KEY) ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = runCatching { ProviderType.valueOf(o.getString("t")) }.getOrNull() ?: return@mapNotNull null
            ProviderConfig(t, o.getString("k"), o.getString("m"), o.optBoolean("v", true))
        }
    } catch (e: Exception) { emptyList() }

    private companion object { const val KEY = "providers_v1" }
}
