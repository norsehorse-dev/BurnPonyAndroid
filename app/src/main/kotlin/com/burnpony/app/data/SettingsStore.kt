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

    // First-run walkthrough. False until the tour is finished or skipped;
    // Settings flips it back to false to replay it. A StateFlow rather than
    // a plain read so the replay row takes effect on the next frame instead
    // of on the next process start.
    private val _onboardingCompleted = MutableStateFlow(resolveOnboardingState())
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted

    /**
     * Upgrade guard. KEY_ONBOARDING did not exist before 1.1.0, so a naive
     * getBoolean(..., false) would drop every existing 1.0.x user into a
     * full-screen first-run tour on upgrade. Anyone whose prefs already
     * carry a remembered note option or a custom relay has demonstrably
     * used the app, so they start as "already onboarded". The verdict is
     * written back immediately, making this a one-time migration rather
     * than a check that re-runs on every launch.
     */
    private fun resolveOnboardingState(): Boolean {
        if (prefs.contains(KEY_ONBOARDING)) {
            return prefs.getBoolean(KEY_ONBOARDING, false)
        }
        val looksLikeExistingInstall = prefs.contains(KEY_SERVER) ||
            prefs.contains(KEY_LAST_VIEWS) ||
            prefs.contains(KEY_LAST_EXPIRY) ||
            prefs.contains(KEY_LAST_AUTOHIDE)
        prefs.edit().putBoolean(KEY_ONBOARDING, looksLikeExistingInstall).apply()
        return looksLikeExistingInstall
    }

    fun setOnboardingCompleted(value: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()
        _onboardingCompleted.value = value
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

    // Remembered like auto-hide rather than reset like the passphrase: it is a
    // presentation preference about the recipient's screen, not a second
    // factor, so a sticky value cannot cause someone to send an unprotected
    // note believing it is protected.
    var lastNoSelect: Boolean
        get() = prefs.getBoolean(KEY_LAST_NO_SELECT, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_NO_SELECT, value).apply()

    companion object {
        private const val KEY_SERVER = "serverBaseURL"
        private const val KEY_LAST_VIEWS = "lastViewsAllowed"
        private const val KEY_LAST_EXPIRY = "lastExpirySeconds"
        private const val KEY_LAST_AUTOHIDE = "lastAutoHideSeconds"
        private const val KEY_LAST_NO_SELECT = "lastNoSelect"
        private const val KEY_ONBOARDING = "onboardingCompleted"
    }
}
