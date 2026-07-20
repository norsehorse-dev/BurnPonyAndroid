//
// RoundTripTest.kt
// Random-material round trips through the public API, serializer escaping
// edge cases, and share-link assembly with fragment flags.
//

package com.burnpony.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundTripTest {

    @Test
    fun roundTripWithoutPassword() {
        val (envelope, key) = BurnPonyCrypto.encryptNote("hello pony", 0, null)
        assertEquals(false, envelope.pw)
        val payload = BurnPonyCrypto.decryptNote(envelope, key, null)
        assertEquals("hello pony", payload.text)
        assertEquals(0, payload.autoHideSeconds)
    }

    @Test
    fun roundTripWithPassword() {
        val (envelope, key) = BurnPonyCrypto.encryptNote("secret 🔥", 30, "op3n sesame")
        assertEquals(true, envelope.pw)
        val payload = BurnPonyCrypto.decryptNote(envelope, key, "op3n sesame")
        assertEquals("secret 🔥", payload.text)
        assertEquals(30, payload.autoHideSeconds)
    }

    @Test
    fun roundTripRawPasswordVariantsUnlock() {
        // Encrypt with the canonical form, unlock with a messy variant.
        val (envelope, key) = BurnPonyCrypto.encryptNote("x", 0, "hörse power")
        val payload = BurnPonyCrypto.decryptNote(envelope, key, "  hörse power ")
        assertEquals("x", payload.text)
    }

    @Test
    fun roundTripSerializerEdgeCases() {
        val nasty = "quote\" backslash\\ newline\n cr\r tab\t bell\u0007 unit\u001F " +
            "null\u0000 emoji🐴 combining é cjk 你好"
        val (envelope, key) = BurnPonyCrypto.encryptNote(nasty, 120, null)
        assertEquals(nasty, BurnPonyCrypto.decryptNote(envelope, key, null).text)
    }

    @Test
    fun roundTripEmptyNote() {
        val (envelope, key) = BurnPonyCrypto.encryptNote("", 0, null)
        assertEquals("", BurnPonyCrypto.decryptNote(envelope, key, null).text)
    }

    @Test
    fun generatedMaterialHasCorrectShape() {
        val (envelope, key) = BurnPonyCrypto.encryptNote("shape", 0, null)
        assertEquals(32, key.size)
        assertEquals(16, VectorSuite.b64(envelope.salt).size)
        assertEquals(12, VectorSuite.b64(envelope.nonce).size)
        assertEquals(43, BurnPonyCrypto.fragmentEncode(key).length)
        assertEquals(1, envelope.v)
    }

    @Test
    fun freshMaterialDiffersBetweenCalls() {
        val (e1, k1) = BurnPonyCrypto.encryptNote("same text", 0, null)
        val (e2, k2) = BurnPonyCrypto.encryptNote("same text", 0, null)
        assertTrue(!k1.contentEquals(k2))
        assertTrue(e1.salt != e2.salt)
        assertTrue(e1.nonce != e2.nonce)
        assertTrue(e1.ct != e2.ct)
    }

    @Test
    fun shareLinkAssembly() {
        val key = BurnPonyCrypto.fragmentDecode(VectorSuite.vectors[0].keyFragment)
        val fragment = VectorSuite.vectors[0].keyFragment
        val base = "https://burnpony.app"
        assertEquals(
            "$base/n/abc123#$fragment",
            BurnPonyCrypto.shareLink(base, "abc123", key),
        )
        assertEquals(
            "$base/n/abc123#$fragment.p",
            BurnPonyCrypto.shareLink(base, "abc123", key, hasPassword = true, hasReceipt = false),
        )
        assertEquals(
            "$base/n/abc123#$fragment.r",
            BurnPonyCrypto.shareLink(base, "abc123", key, hasPassword = false, hasReceipt = true),
        )
        assertEquals(
            "$base/n/abc123#$fragment.pr",
            BurnPonyCrypto.shareLink(base, "abc123", key, hasPassword = true, hasReceipt = true),
        )
    }

    @Test
    fun envelopeJsonIsDeterministicKeyOrder() {
        val envelope = NoteEnvelope(1, true, "c2FsdA==", "bm9uY2U=", "Y3Q=")
        assertEquals(
            "{\"v\":1,\"pw\":true,\"salt\":\"c2FsdA==\",\"nonce\":\"bm9uY2U=\",\"ct\":\"Y3Q=\"}",
            BurnPonyCrypto.envelopeJson(envelope),
        )
    }

    @Test
    fun payloadJsonEscapingMatrix() {
        assertEquals(
            "{\"v\":1,\"t\":\"a\\\"b\\\\c\\nd\\re\\tf\\bg\\fh\\u0001i\",\"ah\":0}",
            BurnPonyCrypto.payloadJson("a\"b\\c\nd\re\tf\u0008g\u000Ch\u0001i", 0),
        )
        // Non-ASCII stays literal UTF-8, never \u-escaped.
        assertEquals(
            "{\"v\":1,\"t\":\"é🔥你\",\"ah\":10}",
            BurnPonyCrypto.payloadJson("é🔥你", 10),
        )
    }
}
