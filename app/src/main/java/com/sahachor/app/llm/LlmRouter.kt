package com.sahachor.app.llm

import com.sahachor.app.core.SecretStore
import org.json.JSONArray
import org.json.JSONObject

fun interface Chatter { suspend fun chat(system: String?, messages: List<ChatMsg>): Result<String> }

/** Holds the user's keys (encrypted at rest). Per key it falls back across models; across keys and providers it falls back on limits and errors. */
class LlmRouter(private val store: SecretStore, private val clients: (ProviderType) -> LlmClient = ::clientFor) : Chatter {
    @Volatile private var configs: List<ProviderConfig> = load()

    fun providers(): List<ProviderConfig> = configs
    fun hasProvider(): Boolean = configs.any { it.valid }

    @Synchronized
    fun add(cfg: ProviderConfig) {
        configs = listOf(cfg) + configs.filterNot { it.type == cfg.type && it.apiKey == cfg.apiKey } // newest first
        save()
    }

    @Synchronized
    fun removeKey(type: ProviderType, apiKey: String) { configs = configs.filterNot { it.type == type && it.apiKey == apiKey }; save() }

    @Synchronized
    fun remove(type: ProviderType) { configs = configs.filterNot { it.type == type }; save() }

    override suspend fun chat(system: String?, messages: List<ChatMsg>): Result<String> {
        val active = configs.filter { it.valid }
        if (active.isEmpty()) return Result.failure(LlmException(LlmError.NoProvider))
        var last: LlmError = LlmError.NoProvider
        loop@ for (cfg in active) {
            val models = (listOf(cfg.model) + cfg.alt).distinct().take(3)
            when (val o = ModelFallback.run(models) { m -> clients(cfg.type).chat(cfg.apiKey, m, system, messages) }) {
                is KeyOutcome.Ok -> {
                    if (o.model != cfg.model) promote(cfg, o.model) // remember the model that actually answers
                    return Result.success(o.text)
                }
                is KeyOutcome.Failed -> {
                    last = o.error
                    if (o.invalid) markInvalid(cfg)
                    if (o.networkDown) break@loop
                }
            }
        }
        return Result.failure(LlmException(last))
    }

    @Synchronized
    private fun promote(cfg: ProviderConfig, model: String) {
        val order = (listOf(cfg.model) + cfg.alt).filter { it != model }.distinct()
        configs = configs.map { if (it.type == cfg.type && it.apiKey == cfg.apiKey) it.copy(model = model, alt = order) else it }
        save()
    }

    @Synchronized
    private fun markInvalid(cfg: ProviderConfig) {
        configs = configs.map { if (it.type == cfg.type && it.apiKey == cfg.apiKey) it.copy(valid = false) else it }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        configs.forEach {
            arr.put(JSONObject().put("t", it.type.name).put("k", it.apiKey).put("m", it.model).put("v", it.valid).put("a", JSONArray(it.alt)))
        }
        store.put(KEY, arr.toString())
    }

    private fun load(): List<ProviderConfig> = try {
        val arr = JSONArray(store.get(KEY) ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = runCatching { ProviderType.valueOf(o.getString("t")) }.getOrNull() ?: return@mapNotNull null
            val a = o.optJSONArray("a")
            val alt = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
            ProviderConfig(t, o.getString("k"), o.getString("m"), o.optBoolean("v", true), alt)
        }
    } catch (e: Exception) { emptyList() }

    private companion object { const val KEY = "providers_v1" }
}
