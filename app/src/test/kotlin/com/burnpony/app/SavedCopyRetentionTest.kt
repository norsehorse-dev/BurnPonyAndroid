//
// SavedCopyRetentionTest.kt
// "Delete saved copies after": cutoff math and stored-value handling.
//

package com.burnpony.app

import com.burnpony.app.data.SavedCopyRetention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedCopyRetentionTest {

    private val now = 1_783_447_200_000L // 2026-07-07T18:00:00Z
    private val day = 86_400_000L

    @Test
    fun neverMeansNoCutoff() {
        assertNull(SavedCopyRetention.cutoffEpochMs(now, 0))
        assertFalse(SavedCopyRetention.isExpired(0L, now, 0))
    }

    @Test
    fun cutoffIsNowMinusDays() {
        assertEquals(now - 7 * day, SavedCopyRetention.cutoffEpochMs(now, 7))
        assertEquals(now - 30 * day, SavedCopyRetention.cutoffEpochMs(now, 30))
        assertEquals(now - 90 * day, SavedCopyRetention.cutoffEpochMs(now, 90))
    }

    @Test
    fun ninetyDaysDoesNotOverflow() {
        // 90 days in ms exceeds Int range; the math must stay in Long.
        assertTrue(90 * day > Int.MAX_VALUE)
        assertEquals(now - 7_776_000_000L, SavedCopyRetention.cutoffEpochMs(now, 90))
    }

    @Test
    fun boundaryIsStrictlyOlder() {
        val cutoff = now - 7 * day
        assertFalse(SavedCopyRetention.isExpired(cutoff, now, 7))
        assertTrue(SavedCopyRetention.isExpired(cutoff - 1, now, 7))
        assertFalse(SavedCopyRetention.isExpired(now, now, 7))
    }

    @Test
    fun unknownStoredValuesFallBackToNever() {
        assertEquals(0, SavedCopyRetention.normalize(5))
        assertEquals(0, SavedCopyRetention.normalize(-1))
        for (d in SavedCopyRetention.CHOICES_DAYS) assertEquals(d, SavedCopyRetention.normalize(d))
        assertEquals(listOf(0, 7, 30, 90), SavedCopyRetention.CHOICES_DAYS)
    }
}
