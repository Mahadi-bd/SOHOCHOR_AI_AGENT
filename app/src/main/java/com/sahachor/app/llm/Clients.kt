package com.sahachor.app.llm

import org.json.JSONArray
import org.json.JSONObject

interface LlmClient {
    val type: ProviderType
    /** Cheap, token-free call that proves the key works and returns usable model ids. */
    suspend fun listModels(key: String): List<String>
    suspend fun chat(key: String, model: String, system: String?, messages: List<ChatMsg>): String
}

private fun JSONObject.arr(name: String): JSONArray = optJSONArray(name) ?: JSONArray()
private inline fun <T> JSONArray.mapObjects(f: (JSONObject) -> T?): List<T> =
    (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }

private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

private fun parseFail(): Nothing = throw LlmException(LlmError.Unknown("parse"))

class GeminiClient : LlmClient {
    override val type = ProviderType.GEMINI
    private fun h(key: String) = mapOf("x-goog-api-key" to key)

    override suspend fun listModels(key: String): List<String> {
        val j = JSONObject(Http.execute("${type.baseUrl}/models?pageSize=200", h(key), null))
        return j.arr("models").mapObjects { m ->
            val methods = m.arr("supportedGenerationMethods")
            val ok = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            if (ok) m.optString("name").removePrefix("models/").ifBlank { null } else null
        }
    }

    override suspend fun chat(key: String, model: String, system: String?, messages: List<ChatMsg>): String {
        val contents = JSONArray()
        messages.forEach {
            val parts = JSONArray().put(JSONObject().put("text", it.content))
            it.images.forEach { img -> parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64(img)))) }
            contents.put(JSONObject().put("role", if (it.role == "assistant") "model" else "user").put("parts", parts))
        }
        val body = JSONObject().put("contents", contents)
        if (!system.isNullOrBlank()) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        val res = JSONObject(Http.execute("${type.baseUrl}/models/$model:generateContent", h(key), body.toString()))
        val parts = res.arr("candidates").optJSONObject(0)?.optJSONObject("content")?.arr("parts") ?: parseFail()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString().ifBlank { parseFail() }
    }
}

/** Groq, OpenRouter and OpenAI all speak the same chat-completions dialect. */
class OpenAiCompatClient(override val type: ProviderType) : LlmClient {
    private fun h(key: String) = mapOf("Authorization" to "Bearer $key")

    override suspend fun listModels(key: String): List<String> {
        if (type == ProviderType.OPENROUTER) Http.execute("${type.baseUrl}/auth/key", h(key), null) // /models is public, so verify the key separately
        val j = JSONObject(Http.execute("${type.baseUrl}/models", h(key), null))
        return j.arr("data").mapObjects { it.optString("id").ifBlank { null } }
    }

    override suspend fun chat(key: String, model: String, system: String?, messages: List<ChatMsg>): String {
        val arr = JSONArray()
        if (!system.isNullOrBlank()) arr.put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { m ->
            if (m.images.isEmpty()) {
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            } else {
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", m.content))
                m.images.forEach { img -> content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b64(img)))) }
                arr.put(JSONObject().put("role", m.role).put("content", content))
            }
        }
        val body = JSONObject().put("model", model).put("messages", arr)
        val res = JSONObject(Http.execute("${type.baseUrl}/chat/completions", h(key), body.toString()))
        return res.arr("choices").optJSONObject(0)?.optJSONObject("message")?.optString("content")?.ifBlank { null } ?: parseFail()
    }
}

class AnthropicClient : LlmClient {
    override val type = ProviderType.ANTHROPIC
    private fun h(key: String) = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")

    override suspend fun listModels(key: String): List<String> {
        val j = JSONObject(Http.execute("${type.baseUrl}/models?limit=100", h(key), null))
        return j.arr("data").mapObjects { it.optString("id").ifBlank { null } }
    }

    override suspend fun chat(key: String, model: String, system: String?, messages: List<ChatMsg>): String {
        val arr = JSONArray()
        messages.forEach { m ->
            if (m.images.isEmpty()) {
                arr.put(JSONObject().put("role", m.role).put("content", m.content))
            } else {
                val content = JSONArray()
                m.images.forEach { img -> content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64(img)))) }
                content.put(JSONObject().put("type", "text").put("text", m.content))
                arr.put(JSONObject().put("role", m.role).put("content", content))
            }
        }
        val body = JSONObject().put("model", model).put("max_tokens", 4096).put("messages", arr)
        if (!system.isNullOrBlank()) body.put("system", system)
        val res = JSONObject(Http.execute("${type.baseUrl}/messages", h(key), body.toString()))
        val blocks = res.arr("content")
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) blocks.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.let { sb.append(it.optString("text")) }
        return sb.toString().ifBlank { parseFail() }
    }
}

/**
 * GitHub Models: the models behind GitHub's AI offering (usable with a GitHub token that has the "models" permission).
 * Chat uses the standard chat-completions format; only the model list lives at a separate catalog address.
 * This provider is marked experimental because GitHub changes it often.
 */
class GitHubModelsClient : LlmClient {
    override val type = ProviderType.GITHUB
    private val chatClient = OpenAiCompatClient(ProviderType.GITHUB)

    override suspend fun listModels(key: String): List<String> {
        val headers = mapOf("Authorization" to "Bearer $key", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
        val text = Http.execute("https://models.github.ai/catalog/models", headers, null)
        val arr = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text).optJSONArray("data") ?: JSONArray()
        return arr.mapObjects { it.optString("id").ifBlank { null } }
    }

    override suspend fun chat(key: String, model: String, system: String?, messages: List<ChatMsg>): String =
        chatClient.chat(key, model, system, messages)
}

fun clientFor(type: ProviderType): LlmClient = when (type) {
    ProviderType.GEMINI -> GeminiClient()
    ProviderType.ANTHROPIC -> AnthropicClient()
    ProviderType.GITHUB -> GitHubModelsClient()
    else -> OpenAiCompatClient(type)
}
