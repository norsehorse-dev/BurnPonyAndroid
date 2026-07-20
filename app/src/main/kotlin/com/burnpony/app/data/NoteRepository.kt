//
// NoteRepository.kt
// Combines the crypto core, the relay client and the Room store. Mirrors the
// iOS create/refresh/burn flows, including the Diego-round refresh algorithm
// and expiry editing.
//

package com.burnpony.app.data

import com.burnpony.app.data.api.BurnPonyApi
import com.burnpony.app.data.api.BurnPonyApiException
import com.burnpony.app.data.db.SentNoteDao
import com.burnpony.app.data.db.SentNoteEntity
import com.burnpony.app.push.PushRegistrar
import com.burnpony.app.util.Iso8601
import com.burnpony.core.BurnPonyCrypto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** What the Result screen needs after a successful create. */
data class CreatedNote(
    val id: String,
    val shareLink: String,
    val hasPassword: Boolean,
    val expiresAtEpochMs: Long,
)

class NoteRepository(
    private val api: BurnPonyApi,
    private val dao: SentNoteDao,
    private val settings: SettingsStore,
    private val pushRegistrar: PushRegistrar,
    moshi: Moshi,
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
    ): CreatedNote {
        val serverBaseUrl = settings.serverBaseUrl.value
        val (envelope, fragmentKey) = withContext(Dispatchers.Default) {
            BurnPonyCrypto.encryptNote(text, autoHideSeconds, password)
        }
        val envelopeJson = BurnPonyCrypto.envelopeJson(envelope)
        val response = api.createNote(
            baseUrl = serverBaseUrl,
            envelopeJson = envelopeJson,
            viewsAllowed = viewsAllowed,
            expiresIn = expiresInSeconds,
            receiptEnabled = receiptEnabled,
        )
        val now = System.currentTimeMillis()
        val expiresAt = Iso8601.parseEpochMs(response.expiresAt)
            ?: (now + expiresInSeconds * 1000L)
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
            )
        )
        val link = BurnPonyCrypto.shareLink(
            baseUrl = serverBaseUrl,
            noteId = response.id,
            fragmentKey = fragmentKey,
            hasPassword = password != null,
            hasReceipt = receiptEnabled,
        )
        if (receiptEnabled) {
            registerPendingPushNotes()
        }
        return CreatedNote(
            id = response.id,
            shareLink = link,
            hasPassword = password != null,
            expiresAtEpochMs = expiresAt,
        )
    }

    /**
     * Sent refresh, the Diego-round algorithm:
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
            dao.upsert(note.copy(goneFromServer = true))
        }
        for ((note, result) in fetched) {
            result.fold(
                onSuccess = { status ->
                    val receipts = status.receipts.mapNotNull { Iso8601.parseEpochMs(it) }
                    dao.upsert(
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
                        dao.upsert(note.copy(goneFromServer = true))
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
            dao.upsert(note.copy(burned = true))
        } catch (e: BurnPonyApiException.NotFound) {
            dao.upsert(note.copy(goneFromServer = true))
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
            dao.upsert(
                note.copy(
                    expiresAtEpochMs = Iso8601.parseEpochMs(response.expiresAt)
                        ?: (System.currentTimeMillis() + expiresInSeconds * 1000L),
                )
            )
        } catch (e: BurnPonyApiException.NotFound) {
            dao.upsert(note.copy(goneFromServer = true))
        }
    }

    suspend fun removeLocal(note: SentNoteEntity) {
        dao.delete(note)
    }

    /** Clear Inactive: removes every burned/gone local record (and its label with it). */
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
                dao.upsert(note.copy(pushRegistered = true))
            }
        }
    }
}
