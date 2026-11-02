//
// NoteRepository.kt
// Combines the crypto core, the relay client and the Room store. Mirrors the
// iOS create/refresh/burn flows, including the tester-round refresh algorithm
// and expiry editing.
//
// Saved copies (opt-in): when Settings has them on and the sender turns the
// per-note switch on (it starts off), the note text (simple) or the split note text is
// sealed to the device-only Keystore key pair (SavedCopyVault) and stored on
// the sent-note row. Opening a copy needs the user to unlock the key.
// Double bottom notes are never saved, and links, fragment keys, shares and
// passphrases are never stored.
//

package com.burnpony.app.data

import com.burnpony.app.data.api.BurnPonyApi
import com.burnpony.app.data.api.BurnPonyApiException
import com.burnpony.app.data.db.SentNoteDao
import com.burnpony.app.data.db.SentNoteEntity
import com.burnpony.app.push.PushRegistrar
import com.burnpony.app.util.Iso8601
import com.burnpony.core.BurnPonyCrypto
import com.burnpony.core.BurnPonyComposeV2
import com.burnpony.core.BurnPonyCryptoV2
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

enum class NoteKind { SIMPLE, DECOY, SPLIT }

/** One share link. keyIndex is null for single-link notes; set (0-based) for split keys. */
data class CreatedLink(
    val keyIndex: Int?,
    val url: String,
    val hasPassword: Boolean,
)

/** What the Result screen needs after a successful create. */
data class CreatedNote(
    val id: String,
    val kind: NoteKind,
    val links: List<CreatedLink>,
    val expiresAtEpochMs: Long,
) {
    val shareLink: String get() = links.firstOrNull()?.url ?: ""
    val hasPassword: Boolean get() = links.firstOrNull()?.hasPassword ?: false
}

class NoteRepository(
    private val api: BurnPonyApi,
    private val dao: SentNoteDao,
    private val settings: SettingsStore,
    private val pushRegistrar: PushRegistrar,
    moshi: Moshi,
    private val savedCopyVault: SavedCopyVault,
) {

    private val receiptTimesAdapter = moshi.adapter<List<Long>>(
        Types.newParameterizedType(List::class.java, Long::class.javaObjectType)
    )

    fun observeNotes(): Flow<List<SentNoteEntity>> = dao.observeAll()

    fun receiptTimes(note: SentNoteEntity): List<Long> =
        runCatching { receiptTimesAdapter.fromJson(note.receiptTimesJson) }
            .getOrNull() ?: emptyList()

    /** Device-local label for the notification layer; null when unlabeled or unknown. */
    suspend fun labelFor(noteId: String): String? = dao.getById(noteId)?.label

    /**
     * Encrypts on a CPU dispatcher (PBKDF2 is 600k iterations), uploads, and
     * records the note locally. The passphrase is canonicalized inside the
     * core before encrypting. The label never leaves the device.
     */
    suspend fun createNote(
        text: String,
        autoHideSeconds: Int,
        password: String?,
        viewsAllowed: Int,
        expiresInSeconds: Int,
        receiptEnabled: Boolean,
        label: String?,
        noSelect: Boolean = false,
        availableIn: Int? = null,
        instantOpen: Boolean = false,
        saveCopy: Boolean = false,
    ): CreatedNote {
        val serverBaseUrl = settings.serverBaseUrl.value
        val (envelope, fragmentKey) = withContext(Dispatchers.Default) {
            BurnPonyCrypto.encryptNote(text, autoHideSeconds, password, noSelect)
        }
        val envelopeJson = BurnPonyCrypto.envelopeJson(envelope)
        val response = api.createNote(
            baseUrl = serverBaseUrl,
            envelopeJson = envelopeJson,
            viewsAllowed = viewsAllowed,
            expiresIn = expiresInSeconds,
            receiptEnabled = receiptEnabled,
            availableIn = availableIn,
        )
        val now = System.currentTimeMillis()
        val expiresAt = Iso8601.parseEpochMs(response.expiresAt)
            ?: (now + expiresInSeconds * 1000L)
        val savedCopy = if (saveCopy) sealSavedCopy(text, response.id) else null
        dao.upsert(
            SentNoteEntity(
                id = response.id,
                managementToken = response.managementToken,
                serverBaseUrl = serverBaseUrl,
                createdAtEpochMs = now,
                expiresAtEpochMs = expiresAt,
                viewsAllowed = viewsAllowed,
                viewsUsed = 0,
                receiptEnabled = receiptEnabled,
                hasPassword = password != null,
                autoHideSeconds = autoHideSeconds,
                burned = false,
                goneFromServer = false,
                receiptTimesJson = "[]",
                pushRegistered = false,
                label = label?.trim()?.takeIf { it.isNotEmpty() },
                savedCopy = savedCopy,
                noteKind = NOTE_KIND_SIMPLE,
            )
        )
        val link = BurnPonyCrypto.shareLink(
            baseUrl = serverBaseUrl,
            noteId = response.id,
            fragmentKey = fragmentKey,
            hasPassword = password != null,
            hasReceipt = receiptEnabled,
            instantOpen = instantOpen,
        )
        if (receiptEnabled) {
            registerPendingPushNotes()
        }
        return CreatedNote(
            id = response.id,
            kind = NoteKind.SIMPLE,
            links = listOf(CreatedLink(keyIndex = null, url = link, hasPassword = password != null)),
            expiresAtEpochMs = expiresAt,
        )
    }

    /** Double-bottom note: encrypt on the CPU dispatcher, upload, record locally. */
    suspend fun createDecoyNote(
        compartments: List<Pair<String, String>>,
        autoHideSeconds: Int,
        viewsAllowed: Int,
        expiresInSeconds: Int,
        receiptEnabled: Boolean,
        label: String?,
        availableIn: Int? = null,
        instantOpen: Boolean = false,
    ): CreatedNote {
        val serverBaseUrl = settings.serverBaseUrl.value
        val result = withContext(Dispatchers.Default) {
            BurnPonyComposeV2.decoy(
                compartments.map { BurnPonyComposeV2.Compartment(it.first, it.second) },
                autoHideSeconds,
            )
        }
        val envelopeJson = BurnPonyCryptoV2.slottedEnvelopeJson(result.envelope)
        val response = api.createNote(
            baseUrl = serverBaseUrl, envelopeJson = envelopeJson,
            viewsAllowed = viewsAllowed, expiresIn = expiresInSeconds, receiptEnabled = receiptEnabled,
            availableIn = availableIn,
        )
        // Never a saved copy: a copy on the phone would prove the hidden
        // compartment exists.
        val expiresAt = recordNote(
            response, serverBaseUrl, viewsAllowed, expiresInSeconds, receiptEnabled, true, autoHideSeconds, label,
            noteKind = NOTE_KIND_DECOY,
        )
        val link = BurnPonyCrypto.shareLink(
            baseUrl = serverBaseUrl, noteId = response.id, fragmentKey = result.fragmentKey,
            hasPassword = true, hasReceipt = receiptEnabled, instantOpen = instantOpen,
        )
        return CreatedNote(response.id, NoteKind.DECOY, listOf(CreatedLink(null, link, true)), expiresAt)
    }

    /** Split-key (threshold) note: one server note, n share links. Split links
     *  never carry the instant-open flag (or any flag suffix). */
    suspend fun createThresholdNote(
        text: String,
        autoHideSeconds: Int,
        thresholdT: Int,
        totalN: Int,
        passwords: List<String?>,
        viewsAllowed: Int,
        expiresInSeconds: Int,
        receiptEnabled: Boolean,
        label: String?,
        availableIn: Int? = null,
        saveCopy: Boolean = false,
    ): CreatedNote {
        val serverBaseUrl = settings.serverBaseUrl.value
        val result = withContext(Dispatchers.Default) {
            BurnPonyComposeV2.threshold(text, autoHideSeconds, thresholdT, totalN, passwords)
        }
        val envelopeJson = BurnPonyCrypto.envelopeJson(result.envelope)
        val response = api.createNote(
            baseUrl = serverBaseUrl, envelopeJson = envelopeJson,
            viewsAllowed = viewsAllowed, expiresIn = expiresInSeconds, receiptEnabled = receiptEnabled,
            availableIn = availableIn,
        )
        val anyPassword = passwords.any { it != null }
        val expiresAt = recordNote(
            response, serverBaseUrl, viewsAllowed, expiresInSeconds, receiptEnabled, anyPassword, autoHideSeconds, label,
            noteKind = NOTE_KIND_SPLIT,
            savedCopyText = if (saveCopy) text else null,
        )
        val links = result.fragments.mapIndexed { i, f ->
            CreatedLink(i, BurnPonyComposeV2.shareLink(serverBaseUrl, response.id, f), passwords[i] != null)
        }
        return CreatedNote(response.id, NoteKind.SPLIT, links, expiresAt)
    }

    /** Records a created note locally and returns its expiry epoch-ms. */
    private suspend fun recordNote(
        response: com.burnpony.app.data.api.CreateNoteResponse,
        serverBaseUrl: String,
        viewsAllowed: Int,
        expiresInSeconds: Int,
        receiptEnabled: Boolean,
        hasPassword: Boolean,
        autoHideSeconds: Int,
        label: String?,
        noteKind: String,
        savedCopyText: String? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val expiresAt = Iso8601.parseEpochMs(response.expiresAt) ?: (now + expiresInSeconds * 1000L)
        val savedCopy = savedCopyText?.let { sealSavedCopy(it, response.id) }
        dao.upsert(
            SentNoteEntity(
                id = response.id,
                managementToken = response.managementToken,
                serverBaseUrl = serverBaseUrl,
                createdAtEpochMs = now,
                expiresAtEpochMs = expiresAt,
                viewsAllowed = viewsAllowed,
                viewsUsed = 0,
                receiptEnabled = receiptEnabled,
                hasPassword = hasPassword,
                autoHideSeconds = autoHideSeconds,
                burned = false,
                goneFromServer = false,
                receiptTimesJson = "[]",
                pushRegistered = false,
                label = label?.trim()?.takeIf { it.isNotEmpty() },
                savedCopy = savedCopy,
                noteKind = noteKind,
            )
        )
        if (receiptEnabled) registerPendingPushNotes()
        return expiresAt
    }

    /**
     * Sent refresh, the tester-round algorithm:
     * (a) burned and gone notes are final — skipped entirely;
     * (b) locally EXPIRED notes are marked gone with ZERO network. Sound
     *     because the management token exists only on this device, so
     *     nothing else can ever extend an expiry: the local expiresAt is
     *     authoritative for expiry passage;
     * (c) the remaining truly active notes are fetched CONCURRENTLY —
     *     network only inside the concurrent block, all Room mutations
     *     applied afterward on the caller;
     * (d) per-note: success updates counters/receipts/expiry, not_found
     *     marks gone, transient errors keep last known status.
     * (The batch-status endpoint idea is parked by joint decision; do not
     * grow the public API unilaterally.)
     */
    suspend fun refreshAll() {
        registerPendingPushNotes()
        val now = System.currentTimeMillis()
        val all = dao.getAll()

        val locallyExpired = all.filter {
            !it.burned && !it.goneFromServer && it.expiresAtEpochMs <= now
        }
        val active = all.filter {
            !it.burned && !it.goneFromServer && it.expiresAtEpochMs > now
        }

        val fetched: List<Pair<SentNoteEntity, Result<com.burnpony.app.data.api.NoteStatusResponse>>> =
            coroutineScope {
                active.map { note ->
                    async {
                        note to runCatching {
                            api.noteStatus(note.serverBaseUrl, note.id, note.managementToken)
                        }
                    }
                }.awaitAll()
            }

        // Room mutations strictly after the concurrent network block.
        for (note in locallyExpired) {
            upsertKeepingSavedCopy(note.copy(goneFromServer = true))
        }
        for ((note, result) in fetched) {
            result.fold(
                onSuccess = { status ->
                    val receipts = status.receipts.mapNotNull { Iso8601.parseEpochMs(it) }
                    upsertKeepingSavedCopy(
                        note.copy(
                            viewsUsed = status.viewsUsed,
                            burned = status.burned,
                            receiptTimesJson = receiptTimesAdapter.toJson(receipts),
                            expiresAtEpochMs = Iso8601.parseEpochMs(status.expiresAt)
                                ?: note.expiresAtEpochMs,
                        )
                    )
                },
                onFailure = { e ->
                    if (e is BurnPonyApiException.NotFound) {
                        upsertKeepingSavedCopy(note.copy(goneFromServer = true))
                    }
                    // Offline or transient: keep last known status.
                },
            )
        }
    }

    /** Early burn. Throws BurnPonyApiException for the UI on real failures. */
    suspend fun burn(note: SentNoteEntity) {
        try {
            api.burnNote(note.serverBaseUrl, note.id, note.managementToken)
            upsertKeepingSavedCopy(note.copy(burned = true))
        } catch (e: BurnPonyApiException.NotFound) {
            upsertKeepingSavedCopy(note.copy(goneFromServer = true))
        }
    }

    /**
     * Change expiry via PATCH, counted from now. not_found marks the note
     * gone; other failures throw for the UI error string.
     */
    suspend fun changeExpiry(note: SentNoteEntity, expiresInSeconds: Int) {
        try {
            val response = api.updateNoteExpiry(
                note.serverBaseUrl, note.id, note.managementToken, expiresInSeconds
            )
            upsertKeepingSavedCopy(
                note.copy(
                    expiresAtEpochMs = Iso8601.parseEpochMs(response.expiresAt)
                        ?: (System.currentTimeMillis() + expiresInSeconds * 1000L),
                )
            )
        } catch (e: BurnPonyApiException.NotFound) {
            upsertKeepingSavedCopy(note.copy(goneFromServer = true))
        }
    }

    suspend fun removeLocal(note: SentNoteEntity) {
        dao.delete(note)
    }

    /**
     * Clear Inactive: removes every burned/gone local record (and its label
     * with it), except records that still hold a saved copy.
     */
    suspend fun clearInactive() {
        dao.deleteInactive()
    }

    /**
     * Registers pending receipt notes (receiptEnabled && !pushRegistered &&
     * !goneFromServer) with the push backend and persists success. The
     * standard flavor registers against FCM; the foss registrar is a
     * permanent no-op and polling via refresh remains the receipt path.
     * Runs on every Sent Notes refresh, which is the retry mechanism.
     */
    private suspend fun registerPendingPushNotes() {
        val pending = dao.getAll()
            .filter { it.receiptEnabled && !it.pushRegistered && !it.goneFromServer }
        if (pending.isEmpty()) return
        val registeredIds = pushRegistrar.registerPendingNotes(pending).toSet()
        for (note in pending) {
            if (note.id in registeredIds) {
                upsertKeepingSavedCopy(note.copy(pushRegistered = true))
            }
        }
    }

    // ── Saved copies ────────────────────────────────────────────────────

    /**
     * Status updates write back a copy of a row read earlier (refresh holds
     * its snapshot across the network round trip). Take savedCopy from the
     * row as it is NOW, so a copy deleted or purged in the meantime is not
     * written back.
     */
    private suspend fun upsertKeepingSavedCopy(note: SentNoteEntity) {
        dao.upsert(note.copy(savedCopy = dao.savedCopyFor(note.id)))
    }

    /**
     * Seals [text] for [noteId] when saved copies are on in Settings. Needs
     * no authentication (public-key wrap). The note is already on the server
     * at this point, so a sealing failure (no screen lock any more, Keystore
     * error) must not fail the create: it is logged without content and the
     * note simply has no saved copy.
     */
    private suspend fun sealSavedCopy(text: String, noteId: String): String? {
        if (!settings.savedCopiesEnabled) return null
        return withContext(Dispatchers.IO) {
            try {
                savedCopyVault.seal(text, noteId)
            } catch (e: Exception) {
                Log.w(TAG, "Saved copy not kept: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    /**
     * Creates the saved-copy key pair if needed. False when it cannot be
     * created, which in practice means the device has no secure screen lock.
     */
    suspend fun prepareSavedCopyKey(): Boolean = withContext(Dispatchers.IO) {
        try {
            savedCopyVault.ensureKey()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Saved-copy key unavailable: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * A decrypt cipher for unlocking a copy. On API 30+ it goes into the
     * BiometricPrompt CryptoObject; on API 24-29 fetch it after the prompt
     * succeeds. Throws [SavedCopyUnavailableException].
     */
    suspend fun savedCopyUnlockCipher(): Cipher = withContext(Dispatchers.IO) {
        savedCopyVault.unlockCipher()
    }

    /**
     * Opens a saved copy with an authenticated [unlockCipher]. Throws
     * [SavedCopyUnavailableException] when it cannot be opened on this
     * device (no copy, key invalidated, not unlocked, damaged data).
     */
    suspend fun openSavedCopy(noteId: String, unlockCipher: Cipher): String {
        val stored = dao.savedCopyFor(noteId) ?: throw SavedCopyUnavailableException()
        return withContext(Dispatchers.IO) { savedCopyVault.open(stored, noteId, unlockCipher) }
    }

    suspend fun deleteSavedCopy(noteId: String) {
        dao.clearSavedCopy(noteId)
    }

    suspend fun deleteAllSavedCopies() {
        dao.clearAllSavedCopies()
    }

    suspend fun countSavedCopies(): Int = dao.countSavedCopies()

    /**
     * Auto-delete: nulls copies older than the "Delete saved copies after"
     * setting, measured from createdAtEpochMs. The records stay. Runs on app
     * start and whenever the Sent list loads.
     */
    suspend fun purgeExpiredSavedCopies() {
        val cutoff = SavedCopyRetention.cutoffEpochMs(
            System.currentTimeMillis(),
            settings.savedCopiesMaxAgeDays,
        ) ?: return
        dao.clearSavedCopiesOlderThan(cutoff)
    }

    companion object {
        private const val TAG = "BurnPony"

        // Stored in SentNoteEntity.noteKind; same values as iOS.
        const val NOTE_KIND_SIMPLE = "simple"
        const val NOTE_KIND_DECOY = "decoy"
        const val NOTE_KIND_SPLIT = "split"
    }
}
