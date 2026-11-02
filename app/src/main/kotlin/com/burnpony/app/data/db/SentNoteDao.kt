//
// SentNoteDao.kt
//

package com.burnpony.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SentNoteDao {

    @Query("SELECT * FROM sent_notes ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<SentNoteEntity>>

    @Query("SELECT * FROM sent_notes ORDER BY createdAtEpochMs DESC")
    suspend fun getAll(): List<SentNoteEntity>

    @Query("SELECT * FROM sent_notes WHERE id = :id")
    suspend fun getById(id: String): SentNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: SentNoteEntity)

    @Delete
    suspend fun delete(note: SentNoteEntity)

    /**
     * Clear Inactive: every burned or gone local record (labels go with the
     * rows), except records holding a saved copy. Those are deleted one at a
     * time from the note itself.
     */
    @Query("DELETE FROM sent_notes WHERE (burned = 1 OR goneFromServer = 1) AND savedCopy IS NULL")
    suspend fun deleteInactive()

    // Saved copies (v3). Clearing nulls the copy and keeps the record.

    @Query("SELECT savedCopy FROM sent_notes WHERE id = :id")
    suspend fun savedCopyFor(id: String): String?

    @Query("UPDATE sent_notes SET savedCopy = :savedCopy WHERE id = :id")
    suspend fun setSavedCopy(id: String, savedCopy: String)

    @Query("UPDATE sent_notes SET savedCopy = NULL WHERE id = :id")
    suspend fun clearSavedCopy(id: String)

    @Query("UPDATE sent_notes SET savedCopy = NULL WHERE savedCopy IS NOT NULL")
    suspend fun clearAllSavedCopies()

    @Query("UPDATE sent_notes SET savedCopy = NULL WHERE savedCopy IS NOT NULL AND createdAtEpochMs < :cutoffEpochMs")
    suspend fun clearSavedCopiesOlderThan(cutoffEpochMs: Long)

    @Query("SELECT COUNT(*) FROM sent_notes WHERE savedCopy IS NOT NULL")
    suspend fun countSavedCopies(): Int
}
