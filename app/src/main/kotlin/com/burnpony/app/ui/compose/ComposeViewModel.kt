//
// ComposeViewModel.kt
// State + create flow for the Compose screen, mirroring ComposeView.swift.
//
// v2 adds a note mode: Simple (v1), Double bottom (decoy), and Split key
// (threshold). Simple keeps the exact v1 behaviour; the other two build on
// the frozen BurnPonyComposeV2 helpers.
//

package com.burnpony.app.ui.compose

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.burnpony.app.Demo
import com.burnpony.app.R
import com.burnpony.app.data.CreatedNote
import com.burnpony.app.data.NoteRepository
import com.burnpony.app.data.SettingsStore
import com.burnpony.app.data.api.BurnPonyApiException
import com.burnpony.core.BurnPonyCrypto
import com.burnpony.core.BurnPonyCryptoException
import com.burnpony.core.BurnPonyCryptoV2
import com.burnpony.core.BurnPonyLimits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Error to show in the alert; [args] feed positional format specifiers. */
data class UiError(@StringRes val resId: Int, val args: List<Any> = emptyList())

enum class ComposeMode { SIMPLE, DECOY, SPLIT }

/** Reason a decoy note is not yet valid; the screen maps it to a string. */
enum class DecoyProblem { NEED_TWO, NEED_PASS, DISTINCT }

data class DecoyCompartmentUi(val text: String = "", val password: String = "")

private const val MAX_KEYS = 8

data class ComposeUiState(
    val mode: ComposeMode = ComposeMode.SIMPLE,
    // Simple
    val text: String = "",
    val usePassword: Boolean = false,
    val password: String = "",
    val passwordConfirm: String = "",
    // Double bottom
    val compartments: List<DecoyCompartmentUi> = listOf(DecoyCompartmentUi(), DecoyCompartmentUi()),
    // Split key
    val splitText: String = "",
    val splitN: Int = 3,
    val splitT: Int = 2,
    val splitPerKeyPassphrase: Boolean = false,
    val splitKeyPasswords: List<String> = List(MAX_KEYS) { "" },
    // Shared options
    val viewsAllowed: Int = 1,
    val expirySeconds: Int = 86_400,
    // Scheduled availability (shared across all modes). null = available now.
    // Stored as absolute wall-clock epoch millis; converted to seconds-from-now
    // at create() time.
    val availableAtMillis: Long? = null,
    val receiptEnabled: Boolean = false,
    // Per note only: never remembered and never a default, so every fresh
    // form starts with it off. Ignored for split notes; dropped when a
    // receipt is on.
    val instantOpen: Boolean = false,
    // Saved copy (only offered when Settings has saved copies on). Per note,
    // never remembered or defaulted: every fresh form starts with it off,
    // because senders often paste passwords and keeping one must be a
    // deliberate choice. Double bottom notes are never saved, whatever this
    // says.
    val saveCopy: Boolean = false,
    val autoHideSeconds: Int = 0,
    val noSelect: Boolean = false,
    val label: String = "",
    val showingOptions: Boolean = false,
    val showingClearConfirm: Boolean = false,
    val creating: Boolean = false,
    val error: UiError? = null,
    val created: CreatedNote? = null,
) {
    val overLimit: Boolean
        get() = when (mode) {
            ComposeMode.SIMPLE -> text.length > BurnPonyLimits.MAX_NOTE_CHARACTERS
            ComposeMode.SPLIT -> splitText.length > BurnPonyLimits.MAX_NOTE_CHARACTERS
            ComposeMode.DECOY -> compartments.any { it.text.length > BurnPonyLimits.MAX_NOTE_CHARACTERS }
        }

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

    val decoyProblem: DecoyProblem?
        get() {
            val filled = compartments.filter { it.text.isNotEmpty() }
            if (filled.size < 2) return DecoyProblem.NEED_TWO
            if (filled.any { BurnPonyCrypto.canonicalPassword(it.password).isEmpty() }) return DecoyProblem.NEED_PASS
            val pws = filled.map { BurnPonyCrypto.canonicalPassword(it.password) }
            if (pws.toSet().size != pws.size) return DecoyProblem.DISTINCT
            return null
        }

    val splitInvalid: Boolean get() = splitT < 2 || splitT > splitN

    /** Views actually used: decoy notes get a 2+ budget so a compartment can be
     *  opened under pressure and another retrieved later. */
    val effectiveViews: Int get() = if (mode == ComposeMode.DECOY) maxOf(viewsAllowed, 2) else viewsAllowed

    val canCreate: Boolean
        get() = !creating && !overLimit && when (mode) {
            ComposeMode.SIMPLE -> text.isNotEmpty() && passwordProblem == null
            ComposeMode.DECOY -> decoyProblem == null
            ComposeMode.SPLIT -> splitText.isNotEmpty() && !splitInvalid
        }

    val currentEmpty: Boolean
        get() = when (mode) {
            ComposeMode.SIMPLE -> text.isEmpty()
            ComposeMode.SPLIT -> splitText.isEmpty()
            ComposeMode.DECOY -> compartments.all { it.text.isEmpty() }
        }
}

class ComposeViewModel(
    private val repository: NoteRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    // Demo.composeOverride is the debug store-screenshot prefill; the release
    // stub always returns null, so release starts from freshForm() as before.
    private val _state = MutableStateFlow(freshForm().let { Demo.composeOverride(it) ?: it })
    val state: StateFlow<ComposeUiState> = _state

    /** Settings switch; the per-note "Keep a copy" row shows only when on. */
    val savedCopiesEnabled: StateFlow<Boolean> = settings.savedCopiesEnabledFlow

    // Starts from the Settings defaults. The passphrase and instant open are
    // never defaults, so they always start off.
    private fun freshForm() = ComposeUiState(
        viewsAllowed = settings.defaultViewsAllowed,
        expirySeconds = settings.defaultExpirySeconds,
        autoHideSeconds = settings.defaultAutoHideSeconds,
        noSelect = settings.defaultNoSelect,
        receiptEnabled = settings.defaultReceiptEnabled,
    )

    fun setMode(value: ComposeMode) = _state.update { it.copy(mode = value) }

    // Simple
    fun setText(value: String) = _state.update { it.copy(text = value) }
    fun setUsePassword(value: Boolean) = _state.update { it.copy(usePassword = value) }
    fun setPassword(value: String) = _state.update { it.copy(password = value) }
    fun setPasswordConfirm(value: String) = _state.update { it.copy(passwordConfirm = value) }

    // Double bottom
    fun setCompartmentText(index: Int, value: String) = _state.update {
        it.copy(compartments = it.compartments.mapIndexed { i, c -> if (i == index) c.copy(text = value) else c })
    }
    fun setCompartmentPassword(index: Int, value: String) = _state.update {
        it.copy(compartments = it.compartments.mapIndexed { i, c -> if (i == index) c.copy(password = value) else c })
    }
    fun addCompartment() = _state.update {
        if (it.compartments.size >= BurnPonyCryptoV2.DECOY_SLOTS) it
        else it.copy(compartments = it.compartments + DecoyCompartmentUi())
    }

    // Split key
    fun setSplitText(value: String) = _state.update { it.copy(splitText = value) }
    fun setSplitN(value: Int) = _state.update {
        val n = value.coerceIn(2, MAX_KEYS)
        it.copy(splitN = n, splitT = it.splitT.coerceIn(2, n))
    }
    fun setSplitT(value: Int) = _state.update { it.copy(splitT = value.coerceIn(2, it.splitN)) }
    fun setSplitPerKeyPassphrase(value: Boolean) = _state.update { it.copy(splitPerKeyPassphrase = value) }
    fun setSplitKeyPassword(index: Int, value: String) = _state.update {
        it.copy(splitKeyPasswords = it.splitKeyPasswords.mapIndexed { i, p -> if (i == index) value else p })
    }

    // Shared options
    fun setViewsAllowed(value: Int) = _state.update { it.copy(viewsAllowed = value.coerceIn(1, 100)) }
    fun setExpirySeconds(value: Int) = _state.update { it.copy(expirySeconds = value) }
    /** Set the scheduled "available after" wall-clock time; null clears it. */
    fun setAvailableAtMillis(value: Long?) = _state.update { it.copy(availableAtMillis = value) }
    /** A receipt needs the confirmation screen, so turning one on also turns instant open off. */
    fun setReceiptEnabled(value: Boolean) = _state.update {
        if (value) it.copy(receiptEnabled = true, instantOpen = false) else it.copy(receiptEnabled = false)
    }
    fun setInstantOpen(value: Boolean) = _state.update {
        it.copy(instantOpen = value && !it.receiptEnabled)
    }
    fun setSaveCopy(value: Boolean) = _state.update { it.copy(saveCopy = value) }
    fun setAutoHideSeconds(value: Int) = _state.update { it.copy(autoHideSeconds = value) }
    fun setNoSelect(value: Boolean) = _state.update { it.copy(noSelect = value) }
    fun setLabel(value: String) = _state.update { it.copy(label = value) }
    fun setShowingOptions(value: Boolean) = _state.update { it.copy(showingOptions = value) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun requestClear() {
        val s = _state.value
        if (s.currentEmpty) return
        val longEnough = when (s.mode) {
            ComposeMode.SIMPLE -> s.text.length > 200
            ComposeMode.SPLIT -> s.splitText.length > 200
            ComposeMode.DECOY -> true
        }
        if (longEnough) _state.update { it.copy(showingClearConfirm = true) } else clearCurrent()
    }

    fun confirmClear() { clearCurrent(); _state.update { it.copy(showingClearConfirm = false) } }
    fun cancelClear() = _state.update { it.copy(showingClearConfirm = false) }

    private fun clearCurrent() = _state.update {
        when (it.mode) {
            ComposeMode.SIMPLE -> it.copy(text = "")
            ComposeMode.SPLIT -> it.copy(splitText = "")
            ComposeMode.DECOY -> it.copy(compartments = listOf(DecoyCompartmentUi(), DecoyCompartmentUi()))
        }
    }

    fun create() {
        val s = _state.value
        if (!s.canCreate) return
        // Scheduled availability -> seconds from now. Under 1s (or unset) sends
        // nothing; must be strictly before expiry or the server rejects it, so
        // validate locally and surface the same conflict the sheet warns about.
        val availableIn: Int? = s.availableAtMillis?.let { millis ->
            val secs = ((millis - System.currentTimeMillis()) / 1000L).toInt()
            if (secs < 1) null else secs
        }
        if (availableIn != null && availableIn >= s.expirySeconds) {
            _state.update { it.copy(error = UiError(R.string.error_available_after_expiry)) }
            return
        }
        // The Settings switch is read at create time, so turning it off
        // elsewhere wins over a form that still shows the row on.
        val saveCopy = s.saveCopy && settings.savedCopiesEnabled
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            try {
                val created = when (s.mode) {
                    ComposeMode.SIMPLE -> {
                        val effectivePassword = if (s.usePassword) BurnPonyCrypto.canonicalPassword(s.password) else null
                        repository.createNote(
                            text = s.text, autoHideSeconds = s.autoHideSeconds, password = effectivePassword,
                            viewsAllowed = s.viewsAllowed, expiresInSeconds = s.expirySeconds,
                            receiptEnabled = s.receiptEnabled, label = s.label, noSelect = s.noSelect,
                            availableIn = availableIn,
                            instantOpen = s.instantOpen && !s.receiptEnabled,
                            saveCopy = saveCopy,
                        )
                    }
                    ComposeMode.DECOY -> {
                        val comps = s.compartments.filter { it.text.isNotEmpty() }.map { it.text to it.password }
                        repository.createDecoyNote(
                            compartments = comps, autoHideSeconds = s.autoHideSeconds,
                            viewsAllowed = s.effectiveViews, expiresInSeconds = s.expirySeconds,
                            receiptEnabled = s.receiptEnabled, label = s.label,
                            availableIn = availableIn,
                            instantOpen = s.instantOpen && !s.receiptEnabled,
                        )
                    }
                    ComposeMode.SPLIT -> {
                        val passwords: List<String?> = if (s.splitPerKeyPassphrase) {
                            (0 until s.splitN).map { s.splitKeyPasswords[it].ifEmpty { null } }
                        } else {
                            List(s.splitN) { null }
                        }
                        repository.createThresholdNote(
                            text = s.splitText, autoHideSeconds = s.autoHideSeconds,
                            thresholdT = s.splitT, totalN = s.splitN, passwords = passwords,
                            viewsAllowed = s.viewsAllowed, expiresInSeconds = s.expirySeconds,
                            receiptEnabled = s.receiptEnabled, label = s.label,
                            availableIn = availableIn,
                            saveCopy = saveCopy,
                        )
                    }
                }
                _state.update { it.copy(creating = false, created = created) }
            } catch (e: BurnPonyApiException) {
                _state.update { it.copy(creating = false, error = e.toUiError()) }
            } catch (e: BurnPonyCryptoException) {
                _state.update { it.copy(creating = false, error = UiError(R.string.error_encryption)) }
            } catch (e: Exception) {
                _state.update { it.copy(creating = false, error = UiError(R.string.error_generic)) }
            }
        }
    }

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
