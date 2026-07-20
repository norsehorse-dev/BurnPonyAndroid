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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: SentNoteEntity)

    @Delete
    suspend fun delete(note: SentNoteEntity)
}
