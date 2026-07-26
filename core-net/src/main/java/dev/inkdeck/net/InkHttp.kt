package dev.inkdeck.net

import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.conscrypt.Conscrypt
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The one HTTP client for Telegram (Phase 5), market data (Phase 7) and AI providers (Phase 8).
 *
 * ### Why Conscrypt
 *
 * This device is API 27 with a 2017 security provider. Its TLS stack predates several current
 * root CAs and cipher suites, so a plain `HttpsURLConnection` fails handshakes against endpoints
 * that any modern phone reaches without comment — and it fails with a `SSLHandshakeException`
 * that reads like a network fault rather than a stale trust store. [install] puts Conscrypt's
 * current BoringSSL provider at position 1 so every socket in the process uses it.
 *
 * ### Why one client
 *
 * OkHttp pools connections and threads per client. Three features each constructing their own
 * would triple the idle thread count on a 2-core, 550 MB-free device for no benefit. Callers get
 * [client] and add their own interceptors via `newBuilder()` if they must.
 *
 * ### Timeouts
 *
 * Read timeout is deliberately long: Telegram's `getUpdates` long-poll holds the connection open
 * for its full timeout by design, and an AI streaming response can be quiet between chunks.
 * Callers that want a short read timeout should derive a client with `newBuilder()`.
 */
object InkHttp {

    private const val TAG = "InkDeckNet"

    @Volatile
    private var installed = false

    /**
     * Idempotent, and safe to call from any thread. Call once at app start, before the first
     * request — a provider inserted after a socket factory has been cached does nothing.
     */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
            Log.i(TAG, "conscrypt installed")
        } catch (e: Throwable) {
            // Not fatal: on a device whose stock provider is good enough, plain TLS still works.
            // Log it, because if handshakes then fail this is the first thing to check.
            Log.w(TAG, "conscrypt unavailable, falling back to the platform provider", e)
        }
    }

    val client: OkHttpClient by lazy { build() }

    private fun build(): OkHttpClient {
        install()
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            // MODERN_TLS only. There is no reason to let a 2017 stack negotiate down to TLS 1.0
            // when the whole point of Conscrypt here is to make 1.2+ available.
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))

        trustManager()?.let { tm ->
            runCatching {
                val ctx = SSLContext.getInstance("TLS", Conscrypt.newProvider())
                ctx.init(null, arrayOf(tm), null)
                builder.sslSocketFactory(ctx.socketFactory, tm)
            }.onFailure { Log.w(TAG, "conscrypt SSLContext unavailable", it) }
        }

        return builder.build()
    }

    private fun trustManager(): X509TrustManager? = runCatching {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    /**
     * Blocking GET returning the body as text, or null on any failure.
     *
     * Deliberately returns null rather than throwing: every caller here is a background poller
     * or a widget refresh whose correct response to a failure is a "stale" state, not a crash.
     * The reason is logged.
     */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String? =
        runCatching {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(request).execute().use { response -> readBody(url, response) }
        }.onFailure { Log.w(TAG, "GET $url failed", it) }.getOrNull()

    private fun readBody(url: String, response: Response): String? {
        if (!response.isSuccessful) {
            Log.w(TAG, "GET $url -> HTTP ${response.code}")
            return null
        }
        return response.body?.string()
    }
}
