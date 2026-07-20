//
// BurnPonyDatabase.kt
//

package com.burnpony.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SentNoteEntity::class], version = 1, exportSchema = false)
abstract class BurnPonyDatabase : RoomDatabase() {

    abstract fun sentNoteDao(): SentNoteDao

    companion object {
        fun build(context: Context): BurnPonyDatabase =
            Room.databaseBuilder(context, BurnPonyDatabase::class.java, "burnpony.db")
                .build()
    }
}
