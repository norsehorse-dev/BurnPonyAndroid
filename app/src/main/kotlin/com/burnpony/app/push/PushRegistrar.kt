//
// PushRegistrar.kt
// Seam for read-receipt push. The standard flavor provides an FCM
// implementation (Phase 4); the foss flavor stays on the no-op forever —
// receipts arrive via the polling that already exists in SentNotes refresh.
//
// Both flavors' PushSupport objects expose the same surface consumed by the
// main source set: createRegistrar(context, api) and
// wantsNotificationPermission.
//

package com.burnpony.app.push

import com.burnpony.app.data.db.SentNoteEntity

interface PushRegistrar {
    /**
     * Attempts to register the given pending receipt notes with the push
     * backend. Returns the IDs that were successfully registered so the
     * repository can mark them pushRegistered; failures are silent and
     * retried on the next Sent Notes refresh.
     */
    suspend fun registerPendingNotes(notes: List<SentNoteEntity>): List<String>
}

object NoOpPushRegistrar : PushRegistrar {
    override suspend fun registerPendingNotes(notes: List<SentNoteEntity>): List<String> = emptyList()
}
