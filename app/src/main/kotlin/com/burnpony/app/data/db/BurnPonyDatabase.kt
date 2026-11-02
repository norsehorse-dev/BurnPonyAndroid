//
// BurnPonyDatabase.kt
// v2: additive label column (Tester round B4). Migrations are additive-only;
// existing sent notes are never lost to a schema change.
// v3: additive savedCopy (opt-in encrypted copy of the note text) and
// noteKind ("simple"/"decoy"/"split") columns.
//

package com.burnpony.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SentNoteEntity::class], version = 3, exportSchema = false)
abstract class BurnPonyDatabase : RoomDatabase() {

    abstract fun sentNoteDao(): SentNoteDao

    companion object {

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sent_notes ADD COLUMN label TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(SentNotesMigrations.ADD_SAVED_COPY)
                db.execSQL(SentNotesMigrations.ADD_NOTE_KIND)
            }
        }

        fun build(context: Context): BurnPonyDatabase =
            Room.databaseBuilder(context, BurnPonyDatabase::class.java, "burnpony.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}

/**
 * Migration SQL kept outside the Room classes so the JVM unit tests can
 * check it without loading Room. Additive only: nullable TEXT columns.
 */
internal object SentNotesMigrations {
    const val ADD_SAVED_COPY = "ALTER TABLE sent_notes ADD COLUMN savedCopy TEXT"
    const val ADD_NOTE_KIND = "ALTER TABLE sent_notes ADD COLUMN noteKind TEXT"
    val V2_TO_V3: List<String> = listOf(ADD_SAVED_COPY, ADD_NOTE_KIND)
}
