//
// SavedCopyRetention.kt
// Auto-delete for saved copies ("Delete saved copies after"). Age is
// measured from the note's createdAtEpochMs. Purging nulls the copy and
// keeps the sent-note record. Pure Kotlin so it is covered by JVM tests.
//

package com.burnpony.app.data

object SavedCopyRetention {

    /** Allowed values for the setting, in days; 0 means never delete. */
    val CHOICES_DAYS: List<Int> = listOf(0, 7, 30, 90)

    private const val DAY_MS = 86_400_000L

    /** Unknown stored values fall back to "never" rather than deleting anything. */
    fun normalize(maxAgeDays: Int): Int = if (maxAgeDays in CHOICES_DAYS) maxAgeDays else 0

    /**
     * Copies of notes created strictly before the returned instant are
     * purged. Null when auto-delete is off.
     */
    fun cutoffEpochMs(nowEpochMs: Long, maxAgeDays: Int): Long? {
        if (maxAgeDays <= 0) return null
        return nowEpochMs - maxAgeDays * DAY_MS
    }

    fun isExpired(createdAtEpochMs: Long, nowEpochMs: Long, maxAgeDays: Int): Boolean {
        val cutoff = cutoffEpochMs(nowEpochMs, maxAgeDays) ?: return false
        return createdAtEpochMs < cutoff
    }
}
