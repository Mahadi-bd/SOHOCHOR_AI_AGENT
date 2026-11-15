package com.sahachor.app.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class KeyOutcome {
    data class Ok(val text: String, val model: String) : KeyOutcome()
    /** invalid: this key is bad. networkDown: no point trying anything else. */
    data class Failed(val error: LlmError, val invalid: Boolean, val networkDown: Boolean) : KeyOutcome()
}

/** Tries a key's models in order. A slow, missing or overloaded model never blocks the others. */
object ModelFallback {
    suspend fun run(models: List<String>, call: suspend (String) -> String): KeyOutcome {
        var last: LlmError = LlmError.NoModel
        for (m in models) {
            try {
                return KeyOutcome.Ok(call(m), m)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                val err = e.error
                last = err
                if (err is LlmError.InvalidKey) return KeyOutcome.Failed(err, invalid = true, networkDown = false)
                if (err is LlmError.Offline && err.reason != "TIMEOUT") return KeyOutcome.Failed(err, invalid = false, networkDown = true)
                if (err is LlmError.NoCredit) return KeyOutcome.Failed(err, invalid = false, networkDown = false)
                // timeout, rate limit, model missing, server error: try the next model
            } catch (e: Exception) {
                last = LlmError.Unknown(e.javaClass.simpleName)
            }
        }
        return KeyOutcome.Failed(last, invalid = false, networkDown = false)
    }
}

data class ProbeResult(val model: String, val ok: Boolean, val ms: Long, val error: LlmError?)
data class Race(val winner: String?, val results: List<ProbeResult>)

/** Sends a tiny test message to several models at once and reports who answers first. */
object ModelProbe {
    suspend fun probe(c: LlmClient, key: String, model: String): ProbeResult {
        val t0 = System.currentTimeMillis()
        return try {
            withContext(FastFail()) { c.chat(key, model, null, listOf(ChatMsg("user", "Reply with: ok"))) }
            ProbeResult(model, true, System.currentTimeMillis() - t0, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            ProbeResult(model, false, System.currentTimeMillis() - t0, e.error)
        } catch (e: Exception) {
            ProbeResult(model, false, System.currentTimeMillis() - t0, LlmError.Unknown(e.javaClass.simpleName))
        }
    }

    /** Returns as soon as one model answers; the others are cancelled (their network calls too). */
    suspend fun race(c: LlmClient, key: String, models: List<String>): Race = coroutineScope {
        val ch = Channel<ProbeResult>(models.size.coerceAtLeast(1))
        val jobs = models.map { m -> launch { ch.send(probe(c, key, m)) } }
        val all = mutableListOf<ProbeResult>()
        var winner: String? = null
        for (i in models.indices) {
            val r = ch.receive()
            all += r
            if (r.ok) { winner = r.model; break }
        }
        jobs.forEach { it.cancel() }
        Race(winner, all)
    }

    suspend fun probeAll(c: LlmClient, key: String, models: List<String>): List<ProbeResult> =
        coroutineScope { models.map { m -> async { probe(c, key, m) } }.awaitAll() }
}
