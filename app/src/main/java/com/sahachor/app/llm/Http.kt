package com.sahachor.app.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /** One automatic retry for transient connection errors (not DNS, not in FastFail mode), then the classified error is thrown. */
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

    /** Fully asynchronous and cancellable: cancelling the coroutine cancels the network call immediately. */
    private suspend fun once(url: String, headers: Map<String, String>, body: String?, c: OkHttpClient): String =
        suspendCancellableCoroutine { cont ->
            val request = try {
                val b = Request.Builder().url(url)
                headers.forEach { (k, v) -> b.header(k, v) }
                if (body != null) b.post(body.toRequestBody(json)) else b.get()
                b.build()
            } catch (e: Exception) {
                cont.resumeWithException(LlmException(LlmError.Unknown(e.javaClass.simpleName)))
                return@suspendCancellableCoroutine
            }
            val call = c.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(LlmException(ioError(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        try {
                            val text = r.body?.string().orEmpty()
                            if (!cont.isActive) return
                            if (r.isSuccessful) cont.resume(text) else cont.resumeWithException(LlmException(classify(r.code, text)))
                        } catch (e: IOException) {
                            if (cont.isActive) cont.resumeWithException(LlmException(ioError(e)))
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(LlmException(LlmError.Unknown(e.javaClass.simpleName)))
                        }
                    }
                }
            })
        }

    private fun ioError(e: IOException): LlmError = when (e) {
        is UnknownHostException -> LlmError.Offline("DNS")
        is SocketTimeoutException, is InterruptedIOException -> LlmError.Offline("TIMEOUT")
        is SSLException -> LlmError.Offline("SSL")
        else -> LlmError.Offline("IO:" + e.javaClass.simpleName + ":" + e.message.orEmpty().take(60))
    }

    fun classify(code: Int, body: String): LlmError {
        val low = body.lowercase()
        return when {
            code == 400 && "location is not supported" in low -> LlmError.Region
            code == 401 || code == 403 -> LlmError.InvalidKey
            code == 400 && ("api key" in low || "api_key" in low || "invalid_api" in low) -> LlmError.InvalidKey
            code == 402 || "insufficient_quota" in low || "credit balance" in low -> LlmError.NoCredit
            code == 429 || "resource_exhausted" in low -> LlmError.RateLimit
            code == 404 -> LlmError.NoModel
            code >= 500 -> LlmError.Server
            else -> LlmError.Unknown("HTTP $code")
        }
    }
}
