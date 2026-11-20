package com.sahachor.app.watch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sahachor.app.agent.UrlGuard
import com.sahachor.app.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

private const val USER_AGENT = "Mozilla/5.0 (Android) Sahachor-Watcher/1.0"

class Fetched(val url: HttpUrl, val bytes: ByteArray, val charsetName: String)

/** Safe downloads: https only, never the local network, every redirect re-checked, size capped. */
object SiteFetcher {
    private val client = Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private suspend fun get(start: String, maxBytes: Int): Fetched? = withContext(Dispatchers.IO) {
        var url = UrlGuard.parse(start) ?: return@withContext null
        repeat(5) {
            if (UrlGuard.resolvesToPrivate(url.host)) return@withContext null
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "*/*").build()
            val resp = try { client.newCall(req).execute() } catch (e: IOException) { return@withContext null }
            resp.use { r ->
                if (r.isRedirect) {
                    val loc = r.header("Location") ?: return@withContext null
                    url = r.request.url.resolve(loc)?.let { UrlGuard.parse(it.toString()) } ?: return@withContext null
                } else {
                    if (!r.isSuccessful) return@withContext null
                    val body = r.body ?: return@withContext null
                    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    body.byteStream().use { s ->
                        while (true) {
                            val n = s.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) return@withContext null
                            out.write(buf, 0, n)
                        }
                    }
                    return@withContext Fetched(url, out.toByteArray(), charset.name())
                }
            }
        }
        null
    }

    suspend fun html(url: String): String? = get(url, 3 * 1024 * 1024)?.let { String(it.bytes, charset(it.charsetName)) }

    suspend fun download(url: String, maxBytes: Int = 15 * 1024 * 1024): ByteArray? = get(url, maxBytes)?.bytes
}

/** Opens a page in a hidden browser so that links built by JavaScript become visible. Used only when the plain page has almost no links. */
object PageRenderer {
    fun decode(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try { JSONTokener(raw).nextValue() as? String } catch (e: Exception) { null }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(ctx: Context, url: String, waitMs: Long = 2500, timeoutMs: Long = 30_000): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String?> { cont ->
                val web = WebView(ctx.applicationContext)
                var finished = false
                fun finish(value: String?) {
                    if (finished) return
                    finished = true
                    if (cont.isActive) cont.resume(value)
                    try { web.stopLoading(); web.destroy() } catch (e: Exception) { /* already destroyed */ }
                }
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                web.settings.userAgentString = USER_AGENT
                web.webViewClient = object : WebViewClient() {
                    // runs on a background thread: block anything that is not https or points into the local network
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val ok = UrlGuard.parse(request.url.toString()) != null && !UrlGuard.resolvesToPrivate(request.url.host.orEmpty())
                        return if (ok) null else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        view.postDelayed({
                            if (!finished) view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw -> finish(decode(raw)) }
                        }, waitMs)
                    }
                }
                cont.invokeOnCancellation { web.post { try { web.stopLoading(); web.destroy() } catch (e: Exception) { /* gone */ } } }
                web.loadUrl(url)
            }
        }
    }
}

/** First pages of a PDF as JPEG images, for AI vision (many government PDFs are scans or use old Bengali fonts that text extraction garbles). */
object PdfPages {
    fun renderJpegs(ctx: Context, pdf: ByteArray, maxPages: Int = 3, widthPx: Int = 1200): List<ByteArray> {
        val file = File.createTempFile("watch", ".pdf", ctx.cacheDir)
        try {
            file.writeBytes(pdf)
            val out = mutableListOf<ByteArray>()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (i in 0 until minOf(renderer.pageCount, maxPages)) {
                        renderer.openPage(i).use { page ->
                            val scale = widthPx.toFloat() / page.width
                            val bmp = Bitmap.createBitmap(widthPx, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val bos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 72, bos)
                            out += bos.toByteArray()
                            bmp.recycle()
                        }
                    }
                }
            }
            return out
        } catch (e: Exception) {
            return emptyList()
        } finally {
            file.delete()
        }
    }
}
