//
// ProxyPrefs.kt
// BurnPony — SOCKS5 / Tor proxy configuration.
//
// Mirrors PGPony's proxy model: an off/orbot/custom SOCKS5 selection backed
// by SharedPreferences (the same "burnpony_settings" store SettingsStore
// uses). Read by HttpClientFactory to build the proxy-aware OkHttpClient.
//
// Optional SOCKS5 username/password. For Tor these are an isolation token:
// any nonempty pair puts BurnPony on its own Tor circuit (IsolateSOCKSAuth),
// separate from other apps sharing the proxy. For a real authenticated SOCKS5
// proxy they are ordinary credentials. Blank means no auth.
//
// Fail-closed: when a proxy is enabled and unreachable the request FAILS.
// There is no silent direct fallback anywhere.
//
// No onion mirror: the BurnPony relay has no first-party .onion, so there is
// nothing to rewrite.
//

package com.burnpony.app.data.api

import android.content.Context
import android.content.pm.PackageManager

object ProxyPrefs {

    const val PREFS = "burnpony_settings"
    const val KEY_MODE = "proxy_mode"              // "off" | "orbot" | "custom"
    const val KEY_CUSTOM_HOST = "proxy_custom_host"
    const val KEY_CUSTOM_PORT = "proxy_custom_port"
    const val KEY_USERNAME = "proxy_username"
    const val KEY_PASSWORD = "proxy_password"

    const val MODE_OFF = "off"
    const val MODE_ORBOT = "orbot"
    const val MODE_CUSTOM = "custom"

    const val ORBOT_HOST = "127.0.0.1"
    const val ORBOT_PORT = 9050
    const val ORBOT_PACKAGE = "org.torproject.android"

    data class Config(
        val mode: String,
        val host: String?,
        val port: Int,
        val username: String?,
        val password: String?,
    ) {
        val enabled: Boolean get() = mode != MODE_OFF
        /** A stable signature so HttpClientFactory rebuilds only on change. */
        val signature: String get() = "$mode|$host|$port|$username|$password"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(context: Context): Config {
        val p = prefs(context)
        val user = p.getString(KEY_USERNAME, "")?.ifBlank { null }
        val pass = p.getString(KEY_PASSWORD, "")?.ifBlank { null }
        return when (val mode = p.getString(KEY_MODE, MODE_OFF) ?: MODE_OFF) {
            MODE_ORBOT -> Config(mode, ORBOT_HOST, ORBOT_PORT, user, pass)
            MODE_CUSTOM -> Config(
                mode,
                p.getString(KEY_CUSTOM_HOST, "")?.ifBlank { null },
                p.getInt(KEY_CUSTOM_PORT, ORBOT_PORT),
                user,
                pass,
            )
            else -> Config(MODE_OFF, null, 0, null, null)
        }
    }

    fun mode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_OFF) ?: MODE_OFF

    fun customHost(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_HOST, "") ?: ""

    fun customPort(context: Context): Int =
        prefs(context).getInt(KEY_CUSTOM_PORT, ORBOT_PORT)

    fun username(context: Context): String =
        prefs(context).getString(KEY_USERNAME, "") ?: ""

    fun password(context: Context): String =
        prefs(context).getString(KEY_PASSWORD, "") ?: ""

    fun setMode(context: Context, mode: String) =
        prefs(context).edit().putString(KEY_MODE, mode).apply()

    fun setCustom(context: Context, host: String, port: Int) =
        prefs(context).edit()
            .putString(KEY_CUSTOM_HOST, host)
            .putInt(KEY_CUSTOM_PORT, port)
            .apply()

    fun setCredentials(context: Context, username: String, password: String) =
        prefs(context).edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()

    /** Is Orbot installed? Drives the one-tap hint in Settings. */
    fun isOrbotInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
