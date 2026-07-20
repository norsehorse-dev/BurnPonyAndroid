//
// CanonicalizationTest.kt
// The passphrase canonicalization matrix: NFC normalization plus trimming of
// leading/trailing whitespace and U+FEFF, applied inside derivation in every
// implementation (added after a real-world trailing-space failure).
// Capitalization and interior spacing are deliberately NOT forgiven.
//

package com.burnpony.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.text.Normalizer

class CanonicalizationTest {

    private val v3 = VectorSuite.vectors[2] // "correct horse battery staple"
    private val v4 = VectorSuite.vectors[3] // unicode passphrase

    private fun derive(v: Vector, password: String): ByteArray =
        BurnPonyCrypto.deriveKey(BurnPonyCrypto.fragmentDecode(v.keyFragment), v.salt, password)

    @Test
    fun nfdInputDerivesSameKey() {
        val nfd = Normalizer.normalize(v4.password!!, Normalizer.Form.NFD)
        assertFalse("NFD form should differ before canonicalization", nfd == v4.password)
        assertArrayEquals(v4.derivedKey, derive(v4, nfd))
    }

    @Test
    fun trailingSpaceDerivesSameKey() {
        assertArrayEquals(v4.derivedKey, derive(v4, v4.password!! + " "))
        assertArrayEquals(v3.derivedKey, derive(v3, v3.password!! + " "))
    }

    @Test
    fun leadingWhitespaceDerivesSameKey() {
        assertArrayEquals(v4.derivedKey, derive(v4, "\t\n " + v4.password!!))
    }

    @Test
    fun bomAndNbspWrappingDerivesSameKey() {
        assertArrayEquals(v4.derivedKey, derive(v4, "\uFEFF" + v4.password!! + "\uFEFF"))
        assertArrayEquals(v4.derivedKey, derive(v4, "\u00A0" + v4.password + "\u00A0"))
    }

    @Test
    fun capitalizationIsRejected() {
        assertFalse(v3.derivedKey.contentEquals(derive(v3, v3.password!!.uppercase())))
    }

    @Test
    fun interiorSpacingIsRejected() {
        assertFalse(v3.derivedKey.contentEquals(derive(v3, v3.password!!.replace(" ", "  "))))
    }

    @Test
    fun canonicalPasswordFunctionMatrix() {
        assertEquals("abc", BurnPonyCrypto.canonicalPassword("  abc  "))
        assertEquals("abc", BurnPonyCrypto.canonicalPassword("\uFEFFabc\uFEFF"))
        assertEquals("abc", BurnPonyCrypto.canonicalPassword("\u00A0abc\u3000"))
        assertEquals("a b", BurnPonyCrypto.canonicalPassword("a b")) // interior kept
        assertEquals("ABC", BurnPonyCrypto.canonicalPassword("ABC")) // case kept
        // NFC: e + combining acute (U+0065 U+0301) becomes precomposed U+00E9
        assertEquals("\u00E9", BurnPonyCrypto.canonicalPassword("e\u0301"))
        assertEquals("", BurnPonyCrypto.canonicalPassword(" \t\n\uFEFF "))
    }
}
