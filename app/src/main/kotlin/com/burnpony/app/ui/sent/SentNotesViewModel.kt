//
// SentNotesViewModel.kt
// Tester rounds: Clear Inactive, Change Expiry (PATCH, counted from now).
// Saved copies: unlock through BiometricPrompt (driven by the screen), open,
// show, delete. The opened text lives only in this ViewModel's state while
// the copy is on screen.
//

package com.burnpony.app.ui.sent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.burnpony.app.R
import com.burnpony.app.data.NoteRepository
import com.burnpony.app.data.SavedCopyUnavailableException
import com.burnpony.app.data.api.BurnPonyApiException
import com.burnpony.app.data.db.SentNoteEntity
import com.burnpony.app.ui.compose.UiError
import com.burnpony.app.ui.compose.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher

/** A saved copy currently shown on screen. */
data class OpenedSavedCopy(val noteId: String, val text: String)

class SentNotesViewModel(
    private val repository: NoteRepository,
) : ViewModel() {

    val notes: StateFlow<List<SentNoteEntity>> = repository.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _error = MutableStateFlow<UiError?>(null)
    val error: StateFlow<UiError?> = _error

    private val _burnTarget = MutableStateFlow<SentNoteEntity?>(null)
    val burnTarget: StateFlow<SentNoteEntity?> = _burnTarget

    private val _expiryTarget = MutableStateFlow<SentNoteEntity?>(null)
    val expiryTarget: StateFlow<SentNoteEntity?> = _expiryTarget

    private val _showClearInactive = MutableStateFlow(false)
    val showClearInactive: StateFlow<Boolean> = _showClearInactive

    private val _openedCopy = MutableStateFlow<OpenedSavedCopy?>(null)
    val openedCopy: StateFlow<OpenedSavedCopy?> = _openedCopy

    fun receiptTimes(note: SentNoteEntity): List<Long> = repository.receiptTimes(note)

    /** Refresh on entry and on pull-to-refresh; offline keeps last known status silently. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                // Auto-delete old saved copies whenever the list loads.
                runCatching { repository.purgeExpiredSavedCopies() }
                repository.refreshAll()
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun requestBurn(note: SentNoteEntity) {
        _burnTarget.value = note
    }

    fun cancelBurn() {
        _burnTarget.value = null
    }

    fun confirmBurn() {
        val note = _burnTarget.value ?: return
        _burnTarget.value = null
        viewModelScope.launch {
            try {
                repository.burn(note)
            } catch (e: BurnPonyApiException) {
                _error.value = e.toUiError()
            } catch (e: Exception) {
                _error.value = UiError(R.string.sent_burn_error)
            }
        }
    }

    // Change Expiry (B11): active notes only; counted from now.
    fun requestExpiryChange(note: SentNoteEntity) {
        if (!note.burned && !note.goneFromServer) {
            _expiryTarget.value = note
        }
    }

    fun cancelExpiryChange() {
        _expiryTarget.value = null
    }

    fun changeExpiry(expiresInSeconds: Int) {
        val note = _expiryTarget.value ?: return
        _expiryTarget.value = null
        viewModelScope.launch {
            try {
                repository.changeExpiry(note, expiresInSeconds)
            } catch (e: Exception) {
                _error.value = UiError(R.string.error_change_expiry)
            }
        }
    }

    // Clear Inactive (B9): confirmation, then every burned/gone record goes.
    fun requestClearInactive() {
        _showClearInactive.value = true
    }

    fun cancelClearInactive() {
        _showClearInactive.value = false
    }

    fun confirmClearInactive() {
        _showClearInactive.value = false
        viewModelScope.launch { repository.clearInactive() }
    }

    fun remove(note: SentNoteEntity) {
        viewModelScope.launch { repository.removeLocal(note) }
    }

    fun dismissError() {
        _error.value = null
    }

    // ── Saved copies ────────────────────────────────────────────────────

    /**
     * API 30+: builds the decrypt cipher that BiometricPrompt unlocks as a
     * CryptoObject, then hands it to [onReady] on the main thread.
     */
    fun prepareSavedCopyUnlock(onReady: (Cipher) -> Unit) {
        viewModelScope.launch {
            val cipher = try {
                repository.savedCopyUnlockCipher()
            } catch (e: Exception) {
                reportSavedCopyUnavailable()
                return@launch
            }
            onReady(cipher)
        }
    }

    /**
     * After a successful prompt. [unlockedCipher] is the CryptoObject cipher
     * on API 30+; null on API 24-29, where the key is unlocked for a short
     * window and the cipher is created now.
     */
    fun openSavedCopy(noteId: String, unlockedCipher: Cipher?) {
        viewModelScope.launch {
            try {
                val cipher = unlockedCipher ?: repository.savedCopyUnlockCipher()
                _openedCopy.value = OpenedSavedCopy(noteId, repository.openSavedCopy(noteId, cipher))
            } catch (e: SavedCopyUnavailableException) {
                reportSavedCopyUnavailable()
            } catch (e: Exception) {
                reportSavedCopyUnavailable()
            }
        }
    }

    fun reportSavedCopyUnavailable() {
        _error.value = UiError(R.string.saved_copy_unavailable)
    }

    fun closeSavedCopy() {
        _openedCopy.value = null
    }

    fun deleteOpenedSavedCopy() {
        val opened = _openedCopy.value ?: return
        _openedCopy.value = null
        viewModelScope.launch { repository.deleteSavedCopy(opened.noteId) }
    }

    override fun onCleared() {
        _openedCopy.value = null
        super.onCleared()
    }
}
