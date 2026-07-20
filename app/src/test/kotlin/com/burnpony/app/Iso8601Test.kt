//
// Iso8601Test.kt
// Server timestamps: ISO 8601 UTC with Z, no fractional seconds.
//

package com.burnpony.app

import com.burnpony.app.util.Iso8601
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Iso8601Test {

    @Test
    fun parsesContractTimestamps() {
        // 2026-07-07T18:00:00Z = 1783447200000 ms
        assertEquals(1_783_447_200_000L, Iso8601.parseEpochMs("2026-07-07T18:00:00Z"))
        assertEquals(0L, Iso8601.parseEpochMs("1970-01-01T00:00:00Z"))
    }

    @Test
    fun formatsBackToContractShape() {
        assertEquals("2026-07-07T18:00:00Z", Iso8601.format(1_783_447_200_000L))
        val now = 1_752_112_800_000L
        assertEquals(now, Iso8601.parseEpochMs(Iso8601.format(now)))
    }

    @Test
    fun rejectsMalformedInput() {
        assertNull(Iso8601.parseEpochMs(null))
        assertNull(Iso8601.parseEpochMs(""))
        assertNull(Iso8601.parseEpochMs("2026-07-07 18:00:00"))
        assertNull(Iso8601.parseEpochMs("2026-07-07T18:00:00.123Z")) // no fractional seconds in the contract
        assertNull(Iso8601.parseEpochMs("2026-07-07T18:00:00+02:00"))
        assertNull(Iso8601.parseEpochMs("2026-07-07T18:00:00Zjunk"))
    }
}
