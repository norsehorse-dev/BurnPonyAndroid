//
// SentNotesMigrationTest.kt
// The v2 -> v3 migration stays additive (nullable TEXT columns only) and
// matches the entity, and existing constructor callers keep working.
//

package com.burnpony.app

import com.burnpony.app.data.db.SentNoteEntity
import com.burnpony.app.data.db.SentNotesMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentNotesMigrationTest {

    private val additive = Regex("^ALTER TABLE sent_notes ADD COLUMN (\\w+) TEXT$")

    @Test
    fun v2ToV3IsTwoAdditiveTextColumns() {
        val columns = SentNotesMigrations.V2_TO_V3.map { sql ->
            val match = additive.matchEntire(sql)
            assertTrue("not additive: $sql", match != null)
            match!!.groupValues[1]
        }
        assertEquals(listOf("savedCopy", "noteKind"), columns)
    }

    @Test
    fun columnsExistOnTheEntity() {
        val fields = SentNoteEntity::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("savedCopy" in fields)
        assertTrue("noteKind" in fields)
    }

    @Test
    fun newColumnsDefaultToNull() {
        val note = SentNoteEntity(
            id = "n1",
            managementToken = "t",
            serverBaseUrl = "https://example.invalid",
            createdAtEpochMs = 0L,
            expiresAtEpochMs = 1L,
            viewsAllowed = 1,
            viewsUsed = 0,
            receiptEnabled = false,
            hasPassword = false,
            autoHideSeconds = 0,
            burned = false,
            goneFromServer = false,
            receiptTimesJson = "[]",
            pushRegistered = false,
        )
        assertNull(note.label)
        assertNull(note.savedCopy)
        assertNull(note.noteKind)
    }
}
