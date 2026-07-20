//
// Iso8601.kt
// Server timestamps: ISO 8601 UTC with Z, no fractional seconds
// ("2026-07-07T18:00:00Z"). SimpleDateFormat is used instead of java.time so
// minSdk 24 needs no core-library desugaring.
//

package com.burnpony.app.util

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object Iso8601 {

    private fun formatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }

    /** Parses an ISO 8601 UTC timestamp to epoch milliseconds, or null. */
    fun parseEpochMs(value: String?): Long? {
        if (value == null) return null
        val position = ParsePosition(0)
        val date = formatter().parse(value, position) ?: return null
        if (position.index != value.length) return null
        return date.time
    }

    /** Formats epoch milliseconds as an ISO 8601 UTC timestamp with Z. */
    fun format(epochMs: Long): String = formatter().format(java.util.Date(epochMs))
}
