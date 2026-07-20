//
// BurnPonyNegativeTest.kt
// Negative and boundary cases: wrong/missing password, tampering, wrong key,
// unsupported versions, malformed inputs, and the 50k character cap.
//

package com.burnpony.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BurnPonyNegativeTest {

    private val v1 = VectorSuite.vectors[0]
    private val v3 = VectorSuite.vectors[2] // password vector

    private fun envelopeOf(v: Vector) = NoteEnvelope(
        v = 1,
        pw = v.password != null,
        salt = Base64Codec.encode(v.salt),
        nonce = Base64Codec.encode(v.nonce),
        ct = Base64Codec.encode(v.ciphertext),
    )

    @Test
    fun wrongPasswordFailsAuthentication() {
        // The canonical negative case from the vector file: decrypting v3
        // with password "wrong horse battery staple" MUST fail.
        assertThrows(BurnPonyCryptoException.DecryptionFailed::class.java) {
            BurnPonyCrypto.decryptNote(
                envelopeOf(v3),
                BurnPonyCrypto.fragmentDecode(v3.keyFragment),
                "wrong horse battery staple",
            )
        }
    }

    @Test
    fun missingPasswordFailsAuthentication() {
        assertThrows(BurnPonyCryptoException.DecryptionFailed::class.java) {
            BurnPonyCrypto.decryptNote(
                envelopeOf(v3), BurnPonyCrypto.fragmentDecode(v3.keyFragment), null
            )
        }
    }

    @Test
    fun tamperedCiphertextFails() {
        val tampered = v1.ciphertext.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertThrows(BurnPonyCryptoException.DecryptionFailed::class.java) {
            BurnPonyCrypto.decryptNote(
                envelopeOf(v1).copy(ct = Base64Codec.encode(tampered)),
                BurnPonyCrypto.fragmentDecode(v1.keyFragment),
                null,
            )
        }
    }

    @Test
    fun wrongFragmentKeyFails() {
        val wrong = BurnPonyCrypto.fragmentDecode(v1.keyFragment)
            .also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(BurnPonyCryptoException.DecryptionFailed::class.java) {
            BurnPonyCrypto.decryptNote(envelopeOf(v1), wrong, null)
        }
    }

    @Test
    fun unsupportedEnvelopeVersionFails() {
        assertThrows(BurnPonyCryptoException.UnsupportedVersion::class.java) {
            BurnPonyCrypto.decryptNote(
                envelopeOf(v1).copy(v = 2),
                BurnPonyCrypto.fragmentDecode(v1.keyFragment),
                null,
            )
        }
    }

    @Test
    fun unsupportedPayloadVersionFails() {
        // Craft a valid envelope whose decrypted payload says v:2.
        val fragmentKey = BurnPonyCrypto.fragmentDecode(v1.keyFragment)
        val key = BurnPonyCrypto.deriveKey(fragmentKey, v1.salt, null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, v1.nonce))
        val ct = cipher.doFinal("{\"v\":2,\"t\":\"future\",\"ah\":0}".toByteArray(Charsets.UTF_8))
        assertThrows(BurnPonyCryptoException.UnsupportedVersion::class.java) {
            BurnPonyCrypto.decryptNote(
                NoteEnvelope(
                    v = 1, pw = false,
                    salt = Base64Codec.encode(v1.salt),
                    nonce = Base64Codec.encode(v1.nonce),
                    ct = Base64Codec.encode(ct),
                ),
                fragmentKey, null,
            )
        }
    }

    @Test
    fun garbagePayloadFails() {
        // Valid encryption of bytes that are not the payload JSON shape.
        val fragmentKey = BurnPonyCrypto.fragmentDecode(v1.keyFragment)
        val key = BurnPonyCrypto.deriveKey(fragmentKey, v1.salt, null)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, v1.nonce))
        val ct = cipher.doFinal("not json at all".toByteArray(Charsets.UTF_8))
        assertThrows(BurnPonyCryptoException.BadEnvelope::class.java) {
            BurnPonyCrypto.decryptNote(
                NoteEnvelope(
                    v = 1, pw = false,
                    salt = Base64Codec.encode(v1.salt),
                    nonce = Base64Codec.encode(v1.nonce),
                    ct = Base64Codec.encode(ct),
                ),
                fragmentKey, null,
            )
        }
    }

    @Test
    fun badFragmentInputsFail() {
        assertThrows(BurnPonyCryptoException.BadFragmentKey::class.java) {
            BurnPonyCrypto.fragmentDecode("too-short")
        }
        assertThrows(BurnPonyCryptoException.BadFragmentKey::class.java) {
            BurnPonyCrypto.fragmentDecode(v1.keyFragment.dropLast(1) + "!")
        }
        assertThrows(BurnPonyCryptoException.BadFragmentKey::class.java) {
            // 33 bytes encoded: one base64url unit longer than a fragment key.
            BurnPonyCrypto.fragmentDecode(BurnPonyCrypto.fragmentEncode(ByteArray(33)))
        }
    }

    @Test
    fun malformedEnvelopeFieldsFail() {
        val fragmentKey = BurnPonyCrypto.fragmentDecode(v1.keyFragment)
        val good = envelopeOf(v1)
        assertThrows(BurnPonyCryptoException.BadEnvelope::class.java) {
            BurnPonyCrypto.decryptNote(good.copy(salt = "###"), fragmentKey, null)
        }
        assertThrows(BurnPonyCryptoException.BadEnvelope::class.java) {
            BurnPonyCrypto.decryptNote(good.copy(nonce = Base64Codec.encode(ByteArray(11))), fragmentKey, null)
        }
        assertThrows(BurnPonyCryptoException.BadEnvelope::class.java) {
            // ct shorter than a GCM tag
            BurnPonyCrypto.decryptNote(good.copy(ct = Base64Codec.encode(ByteArray(15))), fragmentKey, null)
        }
    }

    @Test
    fun wrongSizedMaterialFailsEncryption() {
        assertThrows(BurnPonyCryptoException.BadEnvelope::class.java) {
            BurnPonyCrypto.encryptNote("x", 0, ByteArray(32), ByteArray(15), ByteArray(12), null)
        }
        assertThrows(BurnPonyCryptoException.BadEnvelope::class.java) {
            BurnPonyCrypto.encryptNote("x", 0, ByteArray(32), ByteArray(16), ByteArray(13), null)
        }
        assertThrows(BurnPonyCryptoException.BadFragmentKey::class.java) {
            BurnPonyCrypto.encryptNote("x", 0, ByteArray(31), ByteArray(16), ByteArray(12), null)
        }
    }

    @Test
    fun fiftyThousandBoundary() {
        val atLimit = "x".repeat(BurnPonyLimits.MAX_NOTE_CHARACTERS)
        val (envelope, key) = BurnPonyCrypto.encryptNote(atLimit, 0, null)
        assertNotNull(envelope)
        assertEquals(
            atLimit,
            BurnPonyCrypto.decryptNote(envelope, key, null).text,
        )
        assertThrows(BurnPonyCryptoException.EncodingFailed::class.java) {
            BurnPonyCrypto.encryptNote("x".repeat(BurnPonyLimits.MAX_NOTE_CHARACTERS + 1), 0, null)
        }
    }

    @Test
    fun emptyCanonicalPasswordFailsDerivation() {
        // Must fail deterministically on EVERY API level: the platform
        // SecretKeyFactory would happily derive from an empty password while
        // the pure-JVM fallback cannot, so deriveKey rejects it up front.
        assertThrows(BurnPonyCryptoException.KeyDerivationFailed::class.java) {
            BurnPonyCrypto.deriveKey(ByteArray(32), ByteArray(16), "   ")
        }
        assertThrows(BurnPonyCryptoException.KeyDerivationFailed::class.java) {
            BurnPonyCrypto.deriveKey(ByteArray(32), ByteArray(16), "\uFEFF\t\n")
        }
        assertThrows(BurnPonyCryptoException.KeyDerivationFailed::class.java) {
            BurnPonyCrypto.encryptNote("x", 0, ByteArray(32), ByteArray(16), ByteArray(12), " ")
        }
    }
}
