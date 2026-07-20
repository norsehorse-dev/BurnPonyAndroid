//
// BurnPonyVectorTest.kt
// Phase 1 GATE: byte-exact parity with the canonical cross-implementation
// vectors, proven against CryptoKit (iOS), WebCrypto (viewer), and the
// independent Python generator. Every assertion here is byte-level.
//

package com.burnpony.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnPonyVectorTest {

    @Test
    fun fragmentDecodeYields32BytesAndRoundTrips() {
        for (v in VectorSuite.vectors) {
            val key = BurnPonyCrypto.fragmentDecode(v.keyFragment)
            assertEquals(v.name, 32, key.size)
            assertEquals(v.name, 43, v.keyFragment.length)
            assertEquals(v.name, v.keyFragment, BurnPonyCrypto.fragmentEncode(key))
        }
    }

    @Test
    fun serializerIsByteExact() {
        for (v in VectorSuite.vectors) {
            val serialized = BurnPonyCrypto.payloadJson(v.text, v.autoHideSeconds)
            assertArrayEquals(
                "${v.name}: serializer must reproduce plaintextPayload byte-for-byte",
                v.plaintextPayload.toByteArray(Charsets.UTF_8),
                serialized.toByteArray(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun derivedKeyMatches() {
        for (v in VectorSuite.vectors) {
            val key = BurnPonyCrypto.deriveKey(
                BurnPonyCrypto.fragmentDecode(v.keyFragment), v.salt, v.password
            )
            assertArrayEquals("${v.name}: derived key", v.derivedKey, key)
        }
    }

    @Test
    fun fallbackPbkdf2PathMatchesVectors() {
        // The pure-JVM PBKDF2 used on API 24/25 must be vector-exact too.
        for (v in VectorSuite.vectors.filter { it.password != null }) {
            val fragmentKey = BurnPonyCrypto.fragmentDecode(v.keyFragment)
            val canonical = BurnPonyCrypto.canonicalPassword(v.password!!)
            val stretched = BurnPonyCrypto.pbkdf2Fallback(
                canonical.toByteArray(Charsets.UTF_8), v.salt
            )
            val key = BurnPonyCrypto.hkdfSha256(
                fragmentKey + stretched, v.salt,
                BurnPonyCrypto.INFO_STRING.toByteArray(Charsets.UTF_8), 32,
            )
            assertArrayEquals("${v.name}: fallback-PBKDF2 derived key", v.derivedKey, key)
        }
    }

    @Test
    fun encryptionReproducesCiphertextByteExact() {
        for (v in VectorSuite.vectors) {
            val envelope = BurnPonyCrypto.encryptNote(
                text = v.text,
                autoHideSeconds = v.autoHideSeconds,
                fragmentKey = BurnPonyCrypto.fragmentDecode(v.keyFragment),
                salt = v.salt,
                nonce = v.nonce,
                password = v.password,
            )
            assertEquals("${v.name}: v", 1, envelope.v)
            assertEquals("${v.name}: pw", v.password != null, envelope.pw)
            assertArrayEquals("${v.name}: salt", v.salt, VectorSuite.b64(envelope.salt))
            assertArrayEquals("${v.name}: nonce", v.nonce, VectorSuite.b64(envelope.nonce))
            assertArrayEquals("${v.name}: ct byte-exact", v.ciphertext, VectorSuite.b64(envelope.ct))
        }
    }

    @Test
    fun decryptionReproducesPayload() {
        for (v in VectorSuite.vectors) {
            val envelope = NoteEnvelope(
                v = 1,
                pw = v.password != null,
                salt = Base64Codec.encode(v.salt),
                nonce = Base64Codec.encode(v.nonce),
                ct = Base64Codec.encode(v.ciphertext),
            )
            val payload = BurnPonyCrypto.decryptNote(
                envelope, BurnPonyCrypto.fragmentDecode(v.keyFragment), v.password
            )
            assertEquals("${v.name}: text", v.text, payload.text)
            assertEquals("${v.name}: autoHide", v.autoHideSeconds, payload.autoHideSeconds)
            assertEquals("${v.name}: v", 1, payload.v)
        }
    }

    @Test
    fun envelopeJsonRoundTripsThroughParser() {
        for (v in VectorSuite.vectors) {
            val parsed = BurnPonyCrypto.parseEnvelope(v.envelopeJson)
            assertArrayEquals(v.name, v.salt, VectorSuite.b64(parsed.salt))
            assertArrayEquals(v.name, v.nonce, VectorSuite.b64(parsed.nonce))
            assertArrayEquals(v.name, v.ciphertext, VectorSuite.b64(parsed.ct))
            assertEquals(v.name, v.password != null, parsed.pw)
            assertEquals(v.name, 1, parsed.v)
        }
    }

    @Test
    fun longNoteVectorIsSubstantial() {
        // Guard that the 8.8 KB long-note vector actually exercised size.
        val v5 = VectorSuite.vectors.last()
        assertTrue(v5.plaintextPayload.length > 8_000)
    }
}
