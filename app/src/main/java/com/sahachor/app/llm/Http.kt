package com.sahachor.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /** Runs a request off the main thread and returns the body text or a classified LlmException. */
    suspend fun execute(url: String, headers: Map<String, String>, body: String?): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (body != null) b.post(body.toRequestBody(json)) else b.get()
        try {
            client.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) text else throw LlmException(classify(r.code, text))
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: UnknownHostException) {
            throw LlmException(LlmError.Offline)
        } catch (e: SocketTimeoutException) {
            throw LlmException(LlmError.Offline)
        } catch (e: IOException) {
            throw LlmException(LlmError.Offline)
        } catch (e: Exception) {
            throw LlmException(LlmError.Unknown(e.javaClass.simpleName))
        }
    }

    fun classify(code: Int, body: String): LlmError {
        val low = body.lowercase()
        return when {
            code == 401 || code == 403 -> LlmError.InvalidKey
            code == 400 && ("api key" in low || "api_key" in low || "invalid_api" in low) -> LlmError.InvalidKey
            code == 402 || "insufficient_quota" in low || "credit balance" in low -> LlmError.NoCredit
            code == 429 || "resource_exhausted" in low -> LlmError.RateLimit
            code >= 500 -> LlmError.Server
            else -> LlmError.Unknown("HTTP $code")
        }
    }
}
