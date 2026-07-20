//
// SettingsStore.kt
// SharedPreferences-backed settings: the custom server URL ("Self-hosting
// (advanced)") plus the remembered last-used note options (Diego round B3).
// Views allowed, expiry, and auto-hide persist across notes; passphrase and
// read receipt DELIBERATELY do not — a sticky passphrase toggle is how
// someone sends an unprotected note believing it is protected.
//

package com.burnpony.app.data

import android.content.Context
import com.burnpony.app.data.api.BurnPonyApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("burnpony_settings", Context.MODE_PRIVATE)

    private val _serverBaseUrl = MutableStateFlow(
        prefs.getString(KEY_SERVER, null) ?: BurnPonyApi.DEFAULT_BASE_URL
    )
    val serverBaseUrl: StateFlow<String> = _serverBaseUrl

    fun setServerBaseUrl(url: String) {
        val normalized = url.trimEnd('/')
        prefs.edit().putString(KEY_SERVER, normalized).apply()
        _serverBaseUrl.value = normalized
    }

    fun resetServerBaseUrl() {
        prefs.edit().remove(KEY_SERVER).apply()
        _serverBaseUrl.value = BurnPonyApi.DEFAULT_BASE_URL
    }

    // Last-used note options. Read once when the compose form initializes.
    var lastViewsAllowed: Int
        get() = prefs.getInt(KEY_LAST_VIEWS, 1).coerceIn(1, 100)
        set(value) = prefs.edit().putInt(KEY_LAST_VIEWS, value.coerceIn(1, 100)).apply()

    var lastExpirySeconds: Int
        get() = prefs.getInt(KEY_LAST_EXPIRY, 86_400)
        set(value) = prefs.edit().putInt(KEY_LAST_EXPIRY, value).apply()

    var lastAutoHideSeconds: Int
        get() = prefs.getInt(KEY_LAST_AUTOHIDE, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_AUTOHIDE, value).apply()

    companion object {
        private const val KEY_SERVER = "serverBaseURL"
        private const val KEY_LAST_VIEWS = "lastViewsAllowed"
        private const val KEY_LAST_EXPIRY = "lastExpirySeconds"
        private const val KEY_LAST_AUTOHIDE = "lastAutoHideSeconds"
    }
}
