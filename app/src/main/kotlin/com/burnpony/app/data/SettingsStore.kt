//
// SettingsStore.kt
// SharedPreferences-backed settings: the custom server URL ("Self-hosting
// (advanced)") plus the defaults for new notes. Views allowed, expiry,
// auto-hide, no-select, and read receipt are user-set defaults edited in
// Settings ("Defaults for new notes"); each fresh compose form starts from
// them, and changing an option on one note does not write back. The
// passphrase is DELIBERATELY never stored: a sticky passphrase toggle is how
// someone sends an unprotected note believing it is protected. A read
// receipt is not a protection factor (the recipient is always told about
// it), so it may be a default.
//
// The pref keys keep their pre-2.0 "last..." names so values remembered by
// older versions carry over as the new defaults.
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
            prefs.contains(KEY_LAST_AUTOHIDE) ||
            prefs.contains(KEY_DEFAULT_RECEIPT)
        prefs.edit().putBoolean(KEY_ONBOARDING, looksLikeExistingInstall).apply()
        return looksLikeExistingInstall
    }

    fun setOnboardingCompleted(value: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()
        _onboardingCompleted.value = value
    }

    // Defaults for new notes. Read by the compose form each time it starts a
    // fresh note; written only from Settings. StateFlows so the Settings
    // screen redraws on change.
    private val _defaultViewsAllowed = MutableStateFlow(prefs.getInt(KEY_LAST_VIEWS, 1).coerceIn(1, 100))
    val defaultViewsAllowedFlow: StateFlow<Int> = _defaultViewsAllowed
    var defaultViewsAllowed: Int
        get() = _defaultViewsAllowed.value
        set(value) {
            val v = value.coerceIn(1, 100)
            prefs.edit().putInt(KEY_LAST_VIEWS, v).apply()
            _defaultViewsAllowed.value = v
        }

    private val _defaultExpirySeconds = MutableStateFlow(prefs.getInt(KEY_LAST_EXPIRY, 86_400))
    val defaultExpirySecondsFlow: StateFlow<Int> = _defaultExpirySeconds
    var defaultExpirySeconds: Int
        get() = _defaultExpirySeconds.value
        set(value) {
            prefs.edit().putInt(KEY_LAST_EXPIRY, value).apply()
            _defaultExpirySeconds.value = value
        }

    private val _defaultAutoHideSeconds = MutableStateFlow(prefs.getInt(KEY_LAST_AUTOHIDE, 0))
    val defaultAutoHideSecondsFlow: StateFlow<Int> = _defaultAutoHideSeconds
    var defaultAutoHideSeconds: Int
        get() = _defaultAutoHideSeconds.value
        set(value) {
            prefs.edit().putInt(KEY_LAST_AUTOHIDE, value).apply()
            _defaultAutoHideSeconds.value = value
        }

    // A presentation preference about the recipient's screen, not a second
    // factor, so a default cannot cause someone to send an unprotected note
    // believing it is protected.
    private val _defaultNoSelect = MutableStateFlow(prefs.getBoolean(KEY_LAST_NO_SELECT, false))
    val defaultNoSelectFlow: StateFlow<Boolean> = _defaultNoSelect
    var defaultNoSelect: Boolean
        get() = _defaultNoSelect.value
        set(value) {
            prefs.edit().putBoolean(KEY_LAST_NO_SELECT, value).apply()
            _defaultNoSelect.value = value
        }

    private val _defaultReceiptEnabled = MutableStateFlow(prefs.getBoolean(KEY_DEFAULT_RECEIPT, false))
    val defaultReceiptEnabledFlow: StateFlow<Boolean> = _defaultReceiptEnabled
    var defaultReceiptEnabled: Boolean
        get() = _defaultReceiptEnabled.value
        set(value) {
            prefs.edit().putBoolean(KEY_DEFAULT_RECEIPT, value).apply()
            _defaultReceiptEnabled.value = value
        }

    // Saved copies (opt-in, off by default). When on, the compose form offers
    // a per-note "Keep a copy on this device" switch; the copy is encrypted
    // with a device-only Keystore key (SavedCopyCrypto). Max age 0 = never
    // auto-delete; otherwise one of SavedCopyRetention.CHOICES_DAYS.
    private val _savedCopiesEnabled = MutableStateFlow(prefs.getBoolean(KEY_SAVED_COPIES, false))
    val savedCopiesEnabledFlow: StateFlow<Boolean> = _savedCopiesEnabled
    var savedCopiesEnabled: Boolean
        get() = _savedCopiesEnabled.value
        set(value) {
            prefs.edit().putBoolean(KEY_SAVED_COPIES, value).apply()
            _savedCopiesEnabled.value = value
        }

    private val _savedCopiesMaxAgeDays = MutableStateFlow(
        SavedCopyRetention.normalize(prefs.getInt(KEY_SAVED_COPIES_MAX_AGE, 0))
    )
    val savedCopiesMaxAgeDaysFlow: StateFlow<Int> = _savedCopiesMaxAgeDays
    var savedCopiesMaxAgeDays: Int
        get() = _savedCopiesMaxAgeDays.value
        set(value) {
            val v = SavedCopyRetention.normalize(value)
            prefs.edit().putInt(KEY_SAVED_COPIES_MAX_AGE, v).apply()
            _savedCopiesMaxAgeDays.value = v
        }

    companion object {
        private const val KEY_SAVED_COPIES = "savedCopiesEnabled"
        private const val KEY_SAVED_COPIES_MAX_AGE = "savedCopiesMaxAgeDays"
        private const val KEY_SERVER = "serverBaseURL"
        private const val KEY_LAST_VIEWS = "lastViewsAllowed"
        private const val KEY_LAST_EXPIRY = "lastExpirySeconds"
        private const val KEY_LAST_AUTOHIDE = "lastAutoHideSeconds"
        private const val KEY_LAST_NO_SELECT = "lastNoSelect"
        private const val KEY_DEFAULT_RECEIPT = "defaultReceiptEnabled"
        private const val KEY_ONBOARDING = "onboardingCompleted"
    }
}
