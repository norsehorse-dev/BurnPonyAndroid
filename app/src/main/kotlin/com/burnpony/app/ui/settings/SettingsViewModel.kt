//
// SettingsViewModel.kt
//

package com.burnpony.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.burnpony.app.data.NoteRepository
import com.burnpony.app.data.SettingsStore
import com.burnpony.app.data.api.BurnPonyApi
import com.burnpony.app.data.api.HttpClientFactory
import com.burnpony.app.data.api.ProxyPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SettingsViewModel(
    private val settings: SettingsStore,
    private val appContext: Context,
    private val repository: NoteRepository,
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

    // ── Defaults for new notes ──────────────────────────────────────────
    // Written only here; the compose form reads them when it starts a fresh
    // note. The passphrase is never a default.

    val defaultViewsAllowed: StateFlow<Int> = settings.defaultViewsAllowedFlow
    val defaultExpirySeconds: StateFlow<Int> = settings.defaultExpirySecondsFlow
    val defaultAutoHideSeconds: StateFlow<Int> = settings.defaultAutoHideSecondsFlow
    val defaultNoSelect: StateFlow<Boolean> = settings.defaultNoSelectFlow
    val defaultReceiptEnabled: StateFlow<Boolean> = settings.defaultReceiptEnabledFlow

    fun setDefaultViewsAllowed(value: Int) { settings.defaultViewsAllowed = value.coerceIn(1, 100) }
    fun setDefaultExpirySeconds(value: Int) { settings.defaultExpirySeconds = value }
    fun setDefaultAutoHideSeconds(value: Int) { settings.defaultAutoHideSeconds = value }
    fun setDefaultNoSelect(value: Boolean) { settings.defaultNoSelect = value }
    fun setDefaultReceiptEnabled(value: Boolean) { settings.defaultReceiptEnabled = value }

    // ── Saved copies ────────────────────────────────────────────────────
    // Turning on first creates the Keystore key pair, which requires a
    // secure screen lock; without one the switch stays off and the section
    // says why. Turning off always takes effect; when copies exist, a
    // dialog asks whether to delete them too.

    val savedCopiesEnabled: StateFlow<Boolean> = settings.savedCopiesEnabledFlow
    val savedCopiesMaxAgeDays: StateFlow<Int> = settings.savedCopiesMaxAgeDaysFlow

    private val _savedCopiesNeedLock = MutableStateFlow(false)
    val savedCopiesNeedLock: StateFlow<Boolean> = _savedCopiesNeedLock

    private val _showSavedCopiesOff = MutableStateFlow(false)
    val showSavedCopiesOff: StateFlow<Boolean> = _showSavedCopiesOff

    private var enabling = false

    fun setSavedCopiesEnabled(value: Boolean) {
        if (value) {
            if (enabling) return
            enabling = true
            viewModelScope.launch {
                try {
                    if (repository.prepareSavedCopyKey()) {
                        _savedCopiesNeedLock.value = false
                        settings.savedCopiesEnabled = true
                    } else {
                        _savedCopiesNeedLock.value = true
                    }
                } finally {
                    enabling = false
                }
            }
        } else {
            settings.savedCopiesEnabled = false
            _savedCopiesNeedLock.value = false
            viewModelScope.launch {
                if (repository.countSavedCopies() > 0) _showSavedCopiesOff.value = true
            }
        }
    }

    fun setSavedCopiesMaxAgeDays(value: Int) {
        settings.savedCopiesMaxAgeDays = value
        // Apply a shorter limit right away rather than on the next start.
        viewModelScope.launch { runCatching { repository.purgeExpiredSavedCopies() } }
    }

    fun deleteAllSavedCopies() {
        _showSavedCopiesOff.value = false
        viewModelScope.launch { repository.deleteAllSavedCopies() }
    }

    fun keepSavedCopies() {
        _showSavedCopiesOff.value = false
    }

    /** Settings -> Replay the walkthrough: re-arms the first-run tour. */
    fun replayOnboarding() {
        settings.setOnboardingCompleted(false)
    }

    fun resetServer() {
        settings.resetServerBaseUrl()
        _serverField.value = ""
        _serverProblem.value = false
    }

    // ── Proxy (SOCKS5 / Tor) ────────────────────────────────────────────
    // Backed by ProxyPrefs; every change invalidates HttpClientFactory so the
    // next relay call rebuilds the client and routes through the new setting.

    private val _proxyMode = MutableStateFlow(ProxyPrefs.mode(appContext))
    val proxyMode: StateFlow<String> = _proxyMode

    private val _proxyHost = MutableStateFlow(ProxyPrefs.customHost(appContext))
    val proxyHost: StateFlow<String> = _proxyHost

    private val _proxyPort = MutableStateFlow(ProxyPrefs.customPort(appContext).toString())
    val proxyPort: StateFlow<String> = _proxyPort

    private val _proxyUsername = MutableStateFlow(ProxyPrefs.username(appContext))
    val proxyUsername: StateFlow<String> = _proxyUsername

    private val _proxyPassword = MutableStateFlow(ProxyPrefs.password(appContext))
    val proxyPassword: StateFlow<String> = _proxyPassword

    fun setProxyMode(mode: String) {
        ProxyPrefs.setMode(appContext, mode)
        _proxyMode.value = mode
        HttpClientFactory.invalidate()
    }

    fun setProxyHost(value: String) {
        _proxyHost.value = value
        persistCustomProxy()
    }

    fun setProxyPort(value: String) {
        _proxyPort.value = value.filter { it.isDigit() }.take(5)
        persistCustomProxy()
    }

    private fun persistCustomProxy() {
        val port = _proxyPort.value.toIntOrNull()?.coerceIn(1, 65_535) ?: ProxyPrefs.ORBOT_PORT
        ProxyPrefs.setCustom(appContext, _proxyHost.value.trim(), port)
        // Only a live custom proxy needs the client rebuilt.
        if (_proxyMode.value == ProxyPrefs.MODE_CUSTOM) HttpClientFactory.invalidate()
    }

    // Stream isolation / SOCKS5 auth. Applies to Orbot and Custom, so a change
    // always invalidates the client.
    fun setProxyUsername(value: String) {
        _proxyUsername.value = value
        persistCredentials()
    }

    fun setProxyPassword(value: String) {
        _proxyPassword.value = value
        persistCredentials()
    }

    private fun persistCredentials() {
        ProxyPrefs.setCredentials(appContext, _proxyUsername.value.trim(), _proxyPassword.value)
        if (_proxyMode.value != ProxyPrefs.MODE_OFF) HttpClientFactory.invalidate()
    }

    fun isOrbotInstalled(): Boolean = ProxyPrefs.isOrbotInstalled(appContext)
}
