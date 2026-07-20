//
// SettingsViewModel.kt
//

package com.burnpony.app.ui.settings

import androidx.lifecycle.ViewModel
import com.burnpony.app.data.SettingsStore
import com.burnpony.app.data.api.BurnPonyApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SettingsViewModel(
    private val settings: SettingsStore,
) : ViewModel() {

    val serverBaseUrl: StateFlow<String> = settings.serverBaseUrl

    private val _serverField = MutableStateFlow(
        if (settings.serverBaseUrl.value != BurnPonyApi.DEFAULT_BASE_URL) {
            settings.serverBaseUrl.value
        } else ""
    )
    val serverField: StateFlow<String> = _serverField

    private val _serverProblem = MutableStateFlow(false)
    val serverProblem: StateFlow<Boolean> = _serverProblem

    fun setServerField(value: String) {
        _serverField.value = value
    }

    /** Mirrors SettingsView.applyServer() on iOS: empty resets, else validate. */
    fun applyServer() {
        val trimmed = _serverField.value.trim()
        if (trimmed.isEmpty()) {
            settings.resetServerBaseUrl()
            _serverProblem.value = false
            return
        }
        val url = trimmed.toHttpUrlOrNull()
        if (url == null || (url.scheme != "https" && url.scheme != "http")) {
            _serverProblem.value = true
            return
        }
        _serverProblem.value = false
        settings.setServerBaseUrl(trimmed)
    }

    fun resetServer() {
        settings.resetServerBaseUrl()
        _serverField.value = ""
        _serverProblem.value = false
    }
}
