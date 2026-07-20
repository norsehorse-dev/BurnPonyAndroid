//
// SentNoteEntity.kt
// Device-local record of a created note, mirroring the iOS SentNote model:
// the note ID, the management token, and the settings needed to display
// status. NOTE TEXT IS NEVER STORED; the plaintext exists only in the
// sender's share action and the recipient's browser. Lose the device, lose
// management of the note, which expires on its own regardless.
//
// Diego round: optional device-local label. The label is stored ONLY here
// (nullable column, additive v2 migration) and is NEVER part of any API
// request, the payload, or the envelope; the notification layer reads it
// from this table by note id.
//

package com.burnpony.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sent_notes")
data class SentNoteEntity(
    @PrimaryKey val id: String,
    val managementToken: String,
    val serverBaseUrl: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val viewsAllowed: Int,
    val viewsUsed: Int,
    val receiptEnabled: Boolean,
    val hasPassword: Boolean,
    val autoHideSeconds: Int,
    val burned: Boolean,
    val goneFromServer: Boolean,
    /** JSON array of epoch-millisecond receipt timestamps, e.g. [1751911200000]. */
    val receiptTimesJson: String,
    val pushRegistered: Boolean,
    /** Device-local only; never sent to the server. */
    val label: String? = null,
)
