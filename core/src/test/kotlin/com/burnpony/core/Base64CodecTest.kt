//
// Base64CodecTest.kt
// The hand-rolled base64 codec (used instead of java.util.Base64, which is
// API 26+; the app supports minSdk 24). Strictness matters: envelope fields
// must round trip exactly and malformed input must be rejected, not guessed.
//

package com.burnpony.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class Base64CodecTest {

    @Test
    fun roundTripsAllRemainders() {
        for (size in 0..66) {
            val data = ByteArray(size) { (it * 7 + size).toByte() }
            val encoded = Base64Codec.encode(data)
            assertEquals("padding for size $size", 0, encoded.length % 4)
            assertArrayEquals("size $size", data, Base64Codec.decode(encoded))
        }
    }

    @Test
    fun matchesKnownValues() {
        assertEquals("", Base64Codec.encode(ByteArray(0)))
        assertEquals("AA==", Base64Codec.encode(ByteArray(1)))
        assertEquals("AAA=", Base64Codec.encode(ByteArray(2)))
        assertEquals("AAAA", Base64Codec.encode(ByteArray(3)))
        assertEquals("TWFu", Base64Codec.encode("Man".toByteArray()))
        assertEquals("TWE=", Base64Codec.encode("Ma".toByteArray()))
        assertEquals("+/8=", Base64Codec.encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
    }

    @Test
    fun randomRoundTrips() {
        val rng = Random(20260710)
        repeat(200) {
            val data = rng.nextBytes(rng.nextInt(0, 300))
            assertArrayEquals(data, Base64Codec.decode(Base64Codec.encode(data)))
        }
    }

    @Test
    fun rejectsMalformedInput() {
        assertNull(Base64Codec.decode("A"))          // bad length
        assertNull(Base64Codec.decode("AAA"))        // bad length
        assertNull(Base64Codec.decode("AA=A"))       // padding inside
        assertNull(Base64Codec.decode("=AAA"))       // padding at start
        assertNull(Base64Codec.decode("AA!="))       // bad character
        assertNull(Base64Codec.decode("AAAé"))  // non-ascii
        assertNull(Base64Codec.decode("AB=="))       // non-canonical trailing bits
        assertNull(Base64Codec.decode("ABC="))       // non-canonical trailing bits
    }

    @Test
    fun vectorFieldsRoundTripExactly() {
        for (v in VectorSuite.vectors) {
            assertEquals(Base64Codec.encode(v.salt), Base64Codec.encode(v.salt))
            assertArrayEquals(v.ciphertext, Base64Codec.decode(Base64Codec.encode(v.ciphertext)))
        }
    }
}
