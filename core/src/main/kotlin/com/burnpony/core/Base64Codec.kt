//
// Base64Codec.kt
// BurnPony core (Kotlin)
//
// Standard base64 (RFC 4648, with padding). Hand-rolled so the core has no
// dependency on java.util.Base64 (API 26+; the app supports minSdk 24 without
// core-library desugaring) and stays auditable. Strict decoder: rejects
// characters outside the alphabet, bad padding, and non-canonical trailing
// bits. Proven against the canonical vectors by the unit test suite.
//

package com.burnpony.core

internal object Base64Codec {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val REVERSE = IntArray(128) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
    }

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n shr 18) and 0x3F])
            out.append(ALPHABET[(n shr 12) and 0x3F])
            out.append(ALPHABET[(n shr 6) and 0x3F])
            out.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n shr 18) and 0x3F])
                out.append(ALPHABET[(n shr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or
                    ((data[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n shr 18) and 0x3F])
                out.append(ALPHABET[(n shr 12) and 0x3F])
                out.append(ALPHABET[(n shr 6) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }

    /** Strict decode; returns null on any malformed input. */
    fun decode(s: String): ByteArray? {
        if (s.length % 4 != 0) return null
        if (s.isEmpty()) return ByteArray(0)
        var padding = 0
        if (s.endsWith("==")) padding = 2
        else if (s.endsWith("=")) padding = 1
        if (s.dropLast(padding).contains('=')) return null
        val out = ByteArray(s.length / 4 * 3 - padding)
        var o = 0
        var i = 0
        while (i < s.length) {
            val last = i + 4 == s.length
            val c0 = value(s[i]) ?: return null
            val c1 = value(s[i + 1]) ?: return null
            val c2 = if (last && padding == 2) 0 else value(s[i + 2]) ?: return null
            val c3 = if (last && padding >= 1) 0 else value(s[i + 3]) ?: return null
            // Canonical form: unused trailing bits must be zero.
            if (last && padding == 2 && (c1 and 0x0F) != 0) return null
            if (last && padding == 1 && (c2 and 0x03) != 0) return null
            val n = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            out[o++] = ((n shr 16) and 0xFF).toByte()
            if (!(last && padding == 2)) out[o++] = ((n shr 8) and 0xFF).toByte()
            if (!(last && padding >= 1)) out[o++] = (n and 0xFF).toByte()
            i += 4
        }
        return out
    }

    private fun value(c: Char): Int? {
        if (c.code >= 128) return null
        val v = REVERSE[c.code]
        return if (v < 0) null else v
    }
}
