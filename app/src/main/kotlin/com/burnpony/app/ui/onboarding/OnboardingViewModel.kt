//
// OnboardingViewModel.kt
// State for the walkthrough's live send step. Deliberately NOT a reuse of
// ComposeViewModel: that one restores and then persists the remembered
// views/expiry/auto-hide defaults, and the tour must not leave a
// five-minute expiry sitting in the user's remembered settings.
//
// The tour fixes its note options (see OnboardingSendSheet for why) so this
// holds nothing but the text, the in-flight flag and the outcome.
//

package com.burnpony.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.burnpony.app.R
import com.burnpony.app.data.CreatedNote
import com.burnpony.app.data.NoteRepository
import com.burnpony.app.data.api.BurnPonyApiException
import com.burnpony.app.ui.compose.UiError
import com.burnpony.app.ui.compose.toUiError
import com.burnpony.core.BurnPonyCryptoException
import com.burnpony.core.BurnPonyLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val text: String = "",
    val creating: Boolean = false,
    val created: CreatedNote? = null,
    val error: UiError? = null,
) {
    val overLimit: Boolean get() = text.length > BurnPonyLimits.MAX_NOTE_CHARACTERS
    val canCreate: Boolean
        get() = text.isNotBlank() && !overLimit && !creating
}

class OnboardingViewModel(
    private val repository: NoteRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state

    fun setText(value: String) = _state.update { it.copy(text = value) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Clears the sheet back to an empty compose state (used on cancel). */
    fun reset() {
        _state.value = OnboardingUiState()
    }

    fun create(label: String?) {
        val s = _state.value
        if (!s.canCreate) return
        _state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val created = repository.createNote(
                    text = s.text,
                    autoHideSeconds = TOUR_AUTO_HIDE_SECONDS,
                    password = null,
                    viewsAllowed = TOUR_VIEWS_ALLOWED,
                    expiresInSeconds = TOUR_EXPIRY_SECONDS,
                    receiptEnabled = false,
                    label = label,
                    noSelect = false,
                )
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

    companion object {
        const val TOUR_VIEWS_ALLOWED = 1
        const val TOUR_EXPIRY_SECONDS = 300
        const val TOUR_AUTO_HIDE_SECONDS = 0
    }
}
