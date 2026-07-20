//
// BurnPonyDatabase.kt
// v2: additive label column (Diego round B4). Migrations are additive-only;
// existing sent notes are never lost to a schema change.
//

package com.burnpony.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SentNoteEntity::class], version = 2, exportSchema = false)
abstract class BurnPonyDatabase : RoomDatabase() {

    abstract fun sentNoteDao(): SentNoteDao

    companion object {

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sent_notes ADD COLUMN label TEXT")
            }
        }

        fun build(context: Context): BurnPonyDatabase =
            Room.databaseBuilder(context, BurnPonyDatabase::class.java, "burnpony.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
