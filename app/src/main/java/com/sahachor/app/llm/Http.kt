package com.sahachor.app.llm

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Inet6Address
import javax.net.ssl.SSLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Put in the coroutine context to use short timeouts and no retry (add-key flow and diagnostics). */
internal class FastFail : AbstractCoroutineContextElement(FastFail) { companion object Key : CoroutineContext.Key<FastFail> }

internal object Http {
    /** Plain client used only to talk to the DNS-over-HTTPS servers (it must not use our own resolver). */
    private val bootstrapClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

    private fun makeDoh(url: String, vararg ips: String): Dns? = try {
        DnsOverHttps.Builder().client(bootstrapClient).url(url.toHttpUrl())
            .bootstrapDnsHosts(*ips.map { InetAddress.getByName(it) }.toTypedArray()) // IP literals: no DNS needed to reach the resolver
            .build()
    } catch (e: Exception) { null }

    /** Like Chrome's "secure DNS": used only when the phone's own DNS cannot resolve a name. */
    private val dohResolvers: List<Dns> = listOfNotNull(
        makeDoh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
        makeDoh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
    )

    fun dohLookup(host: String): List<InetAddress>? = dohResolvers.firstNotNullOfOrNull { d ->
        try { d.lookup(host).takeIf { it.isNotEmpty() } } catch (e: Exception) { null }
    }

    /** System DNS first; DoH fallback if it fails. Many ISPs also have a broken IPv6 path, so IPv4 addresses go first. */
    private val resilientDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val found = try { Dns.SYSTEM.lookup(hostname) } catch (e: UnknownHostException) { dohLookup(hostname) ?: throw e }
            return found.sortedBy { it is Inet6Address }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(resilientDns)
        .protocols(listOf(Protocol.HTTP_1_1)) // some networks reset HTTP/2 streams
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()
    private val quickClient: OkHttpClient by lazy { client.newBuilder().readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build() }
    val quickH2: OkHttpClient by lazy { quickClient.newBuilder().protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)).build() }

    /** Runs a request off the main thread and returns the body text or a classified LlmException. */
    /** One automatic retry for transient connection errors (not DNS), then the classified error is thrown. */
    suspend fun execute(url: String, headers: Map<String, String>, body: String?, over: OkHttpClient? = null): String {
        val fast = coroutineContext[FastFail] != null
        var last: LlmException? = null
        repeat(if (fast) 1 else 2) {
            try { return once(url, headers, body, over ?: if (fast) quickClient else client) } catch (e: LlmException) {
                last = e
                val err = e.error
                if (!(err is LlmError.Offline && err.reason != "DNS")) throw e
            }
        }
        throw last ?: LlmException(LlmError.Unknown("retry"))
    }

    private suspend fun once(url: String, headers: Map<String, String>, body: String?, c: OkHttpClient): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (body != null) b.post(body.toRequestBody(json)) else b.get()
        try {
            c.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.isSuccessful) text else throw LlmException(classify(r.code, text))
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: UnknownHostException) {
            throw LlmException(LlmError.Offline("DNS"))
        } catch (e: SocketTimeoutException) {
            throw LlmException(LlmError.Offline("TIMEOUT"))
        } catch (e: SSLException) {
            throw LlmException(LlmError.Offline("SSL"))
        } catch (e: IOException) {
            throw LlmException(LlmError.Offline("IO:" + e.javaClass.simpleName + ":" + e.message.orEmpty().take(60)))
        } catch (e: Exception) {
            throw LlmException(LlmError.Unknown(e.javaClass.simpleName))
        }
    }

    fun classify(code: Int, body: String): LlmError {
        val low = body.lowercase()
        return when {
            code == 400 && "location is not supported" in low -> LlmError.Region
            code == 401 || code == 403 -> LlmError.InvalidKey
            code == 400 && ("api key" in low || "api_key" in low || "invalid_api" in low) -> LlmError.InvalidKey
            code == 402 || "insufficient_quota" in low || "credit balance" in low -> LlmError.NoCredit
            code == 429 || "resource_exhausted" in low -> LlmError.RateLimit
            code >= 500 -> LlmError.Server
            else -> LlmError.Unknown("HTTP $code")
        }
    }
}
