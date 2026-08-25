//
// HttpClientFactory.kt
// BurnPony — the single source of the relay OkHttpClient.
//
// Turn the proxy on once and every relay call (create / status / burn /
// expiry / push) routes through it. The client is cached and rebuilt only
// when the proxy config changes (compared by Config.signature).
//
// When a proxy is set, the client routes through our own SOCKS5 client
// (SocksProxySocketFactory) with SocksProxyDns, so the destination hostname
// resolves at the proxy (no local DNS leak) and, when credentials are set,
// Tor applies stream isolation. A dead proxy makes the request FAIL:
// fail-closed, no direct fallback (BurnPonyApi maps the IOException to
// Network). Tor adds latency, so proxied clients get longer timeouts.
//

package com.burnpony.app.data.api

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientFactory {

    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 20L
    private const val WRITE_TIMEOUT_S = 20L

    // Tor is slower; give proxied requests more headroom.
    private const val TOR_CONNECT_TIMEOUT_S = 30L
    private const val TOR_READ_TIMEOUT_S = 45L
    private const val TOR_WRITE_TIMEOUT_S = 45L

    @Volatile private var cached: OkHttpClient? = null
    @Volatile private var cachedSignature: String? = null

    /** The shared client for the current proxy config. */
    @Synchronized
    fun client(context: Context): OkHttpClient {
        val cfg = ProxyPrefs.config(context)
        val sig = cfg.signature
        val existing = cached
        if (existing != null && cachedSignature == sig) return existing

        val built = build(cfg)
        cached = built
        cachedSignature = sig
        return built
    }

    private fun build(cfg: ProxyPrefs.Config): OkHttpClient {
        val proxied = cfg.enabled && cfg.host != null
        val builder = OkHttpClient.Builder()
        if (proxied) {
            builder
                .socketFactory(
                    SocksProxySocketFactory(cfg.host!!, cfg.port, cfg.username, cfg.password)
                )
                .dns(SocksProxyDns)
                .connectTimeout(TOR_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TOR_READ_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(TOR_WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        } else {
            builder
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        }
        return builder.build()
    }

    /** Force a rebuild on the next client() (call after a settings change). */
    @Synchronized
    fun invalidate() {
        cached = null
        cachedSignature = null
    }
}
