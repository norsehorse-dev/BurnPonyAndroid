//
// ComposeViewModel.kt
// State + create flow for the Compose screen, mirroring ComposeView.swift.
//
// Diego rounds: views/expiry/auto-hide remember their last-used values
// across notes; passphrase and read receipt DELIBERATELY reset for every
// note (a sticky passphrase toggle is how someone sends an unprotected note
// believing it is protected). Device-local label. Clear action with a
// confirmation above 200 characters.
//

package com.burnpony.app.ui.compose

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.burnpony.app.R
import com.burnpony.app.data.CreatedNote
import com.burnpony.app.data.NoteRepository
import com.burnpony.app.data.SettingsStore
import com.burnpony.app.data.api.BurnPonyApiException
import com.burnpony.core.BurnPonyCrypto
import com.burnpony.core.BurnPonyCryptoException
import com.burnpony.core.BurnPonyLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Error to show in the alert; [args] feed positional format specifiers. */
data class UiError(@StringRes val resId: Int, val args: List<Any> = emptyList())

data class ComposeUiState(
    val text: String = "",
    val viewsAllowed: Int = 1,
    val expirySeconds: Int = 86_400,
    val usePassword: Boolean = false,
    val password: String = "",
    val passwordConfirm: String = "",
    val receiptEnabled: Boolean = false,
    val autoHideSeconds: Int = 0,
    val label: String = "",
    val showingOptions: Boolean = false,
    val showingClearConfirm: Boolean = false,
    val creating: Boolean = false,
    val error: UiError? = null,
    val created: CreatedNote? = null,
) {
    val overLimit: Boolean get() = text.length > BurnPonyLimits.MAX_NOTE_CHARACTERS

    @get:StringRes
    val passwordProblem: Int?
        get() {
            if (!usePassword) return null
            val canonical = BurnPonyCrypto.canonicalPassword(password)
            if (canonical.isEmpty()) return R.string.options_passphrase_empty
            if (canonical != BurnPonyCrypto.canonicalPassword(passwordConfirm)) {
                return R.string.options_passphrase_mismatch
            }
            return null
        }

    val canCreate: Boolean
        get() = text.isNotEmpty() && !overLimit && passwordProblem == null && !creating
}

class ComposeViewModel(
    private val repository: NoteRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(freshForm())
    val state: StateFlow<ComposeUiState> = _state

    /** Last-used views/expiry/auto-hide restored; everything else clean. */
    private fun freshForm() = ComposeUiState(
        viewsAllowed = settings.lastViewsAllowed,
        expirySeconds = settings.lastExpirySeconds,
        autoHideSeconds = settings.lastAutoHideSeconds,
    )

    fun setText(value: String) = _state.update { it.copy(text = value) }
    fun setViewsAllowed(value: Int) = _state.update { it.copy(viewsAllowed = value.coerceIn(1, 100)) }
    fun setExpirySeconds(value: Int) = _state.update { it.copy(expirySeconds = value) }
    fun setUsePassword(value: Boolean) = _state.update { it.copy(usePassword = value) }
    fun setPassword(value: String) = _state.update { it.copy(password = value) }
    fun setPasswordConfirm(value: String) = _state.update { it.copy(passwordConfirm = value) }
    fun setReceiptEnabled(value: Boolean) = _state.update { it.copy(receiptEnabled = value) }
    fun setAutoHideSeconds(value: Int) = _state.update { it.copy(autoHideSeconds = value) }
    fun setLabel(value: String) = _state.update { it.copy(label = value) }
    fun setShowingOptions(value: Boolean) = _state.update { it.copy(showingOptions = value) }
    fun dismissError() = _state.update { it.copy(error = null) }

    /** Clear action: instant under 200 characters, confirmation above. */
    fun requestClear() {
        val s = _state.value
        if (s.text.isEmpty()) return
        if (s.text.length > 200) {
            _state.update { it.copy(showingClearConfirm = true) }
        } else {
            _state.update { it.copy(text = "") }
        }
    }

    fun confirmClear() = _state.update { it.copy(text = "", showingClearConfirm = false) }
    fun cancelClear() = _state.update { it.copy(showingClearConfirm = false) }

    fun create() {
        val s = _state.value
        if (!s.canCreate) return
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            try {
                // Canonicalize before encrypting (compare CANONICALIZED forms
                // was already enforced by passwordProblem).
                val effectivePassword = if (s.usePassword) {
                    BurnPonyCrypto.canonicalPassword(s.password)
                } else null
                val created = repository.createNote(
                    text = s.text,
                    autoHideSeconds = s.autoHideSeconds,
                    password = effectivePassword,
                    viewsAllowed = s.viewsAllowed,
                    expiresInSeconds = s.expirySeconds,
                    receiptEnabled = s.receiptEnabled,
                    label = s.label,
                )
                // Persist last-used (B3): views, expiry, auto-hide only.
                settings.lastViewsAllowed = s.viewsAllowed
                settings.lastExpirySeconds = s.expirySeconds
                settings.lastAutoHideSeconds = s.autoHideSeconds
                _state.update { it.copy(creating = false, created = created) }
            } catch (e: BurnPonyApiException) {
                _state.update { it.copy(creating = false, error = e.toUiError()) }
            } catch (e: BurnPonyCryptoException) {
                _state.update {
                    it.copy(creating = false, error = UiError(R.string.error_encryption))
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(creating = false, error = UiError(R.string.error_generic))
                }
            }
        }
    }

    /** Result dismissed via its explicit Done: fresh form with last-used restored. */
    fun resetForm() {
        _state.value = freshForm()
    }
}

fun BurnPonyApiException.toUiError(): UiError = when (this) {
    is BurnPonyApiException.BadServerUrl -> UiError(R.string.error_bad_server_url)
    is BurnPonyApiException.RateLimited -> UiError(R.string.error_rate_limited)
    is BurnPonyApiException.NoteTooLarge -> UiError(R.string.error_note_too_large)
    is BurnPonyApiException.NotFound -> UiError(R.string.error_not_found)
    is BurnPonyApiException.Server -> UiError(R.string.error_server, listOf(code))
    is BurnPonyApiException.Network -> UiError(R.string.error_network)
    is BurnPonyApiException.BadResponse -> UiError(R.string.error_bad_response)
}
