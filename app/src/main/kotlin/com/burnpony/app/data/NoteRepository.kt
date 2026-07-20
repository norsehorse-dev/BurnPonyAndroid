//
// NoteRepository.kt
// Combines the crypto core, the relay client and the Room store. Mirrors the
// flow of ComposeView.create() / SentNotesView.refresh()/burn() on iOS.
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

    /**
     * Encrypts on a CPU dispatcher (PBKDF2 is 600k iterations), uploads, and
     * records the note locally. The passphrase is canonicalized inside the
     * core before encrypting.
     */
    suspend fun createNote(
        text: String,
        autoHideSeconds: Int,
        password: String?,
        viewsAllowed: Int,
        expiresInSeconds: Int,
        receiptEnabled: Boolean,
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

    /** Refresh every note that is not already gone; offline keeps last known status silently. */
    suspend fun refreshAll() {
        registerPendingPushNotes()
        for (note in dao.getAll()) {
            if (!note.goneFromServer) refresh(note)
        }
    }

    suspend fun refresh(note: SentNoteEntity) {
        try {
            val status = api.noteStatus(note.serverBaseUrl, note.id, note.managementToken)
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
        } catch (e: BurnPonyApiException.NotFound) {
            dao.upsert(note.copy(goneFromServer = true))
        } catch (e: BurnPonyApiException) {
            // Offline or transient: keep last known status.
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

    suspend fun removeLocal(note: SentNoteEntity) {
        dao.delete(note)
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
