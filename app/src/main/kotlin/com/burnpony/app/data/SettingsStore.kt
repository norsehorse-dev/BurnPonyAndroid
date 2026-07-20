//
// SettingsStore.kt
// SharedPreferences-backed settings, currently just the custom server URL
// ("Self-hosting (advanced)"). Notes remember the server they were created
// on, so changing this only affects new notes.
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

    companion object {
        private const val KEY_SERVER = "serverBaseURL"
    }
}
