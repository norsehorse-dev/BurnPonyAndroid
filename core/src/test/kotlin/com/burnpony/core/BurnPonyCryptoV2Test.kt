//
// BurnPonyCryptoV2Test.kt
// Phase 2.0-A GATE: byte-exact parity for envelope v2 against the same vectors
// proven under Python, WebCrypto, and Swift. Format FROZEN (Aug 27, 2026).
//

package com.burnpony.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BurnPonyCryptoV2ThresholdTest {

    @Test
    fun shareFragmentsReproducedByteExact() {
        val v = V2VectorSuite.threshold
        val shares = BurnPonyCryptoV2.sssSplit(v.master, v.t, v.n, v.coeff)
        for (s in v.shares) {
            val raw = shares[s.x - 1].second
            val frag = BurnPonyCryptoV2.wrapShare(s.x, v.t, v.n, raw, s.password, s.salt, s.wrapNonce)
            assertEquals("share x=${s.x} fragment", s.fragment, frag)
        }
    }

    @Test
    fun reconstructsMasterFromTOfN() {
        val v = V2VectorSuite.threshold
        val u1 = BurnPonyCryptoV2.unwrapShare(v.shares[0].fragment, v.shares[0].password)
        val u2 = BurnPonyCryptoV2.unwrapShare(v.shares[1].fragment, v.shares[1].password)
        val rec = BurnPonyCryptoV2.sssCombine(listOf(Pair(u1.x, u1.share), Pair(u2.x, u2.share)))
        assertArrayEquals("reconstructed master secret", v.master, rec)
    }

    @Test
    fun encryptionReproducesCiphertextByteExact() {
        val v = V2VectorSuite.threshold
        val u1 = BurnPonyCryptoV2.unwrapShare(v.shares[0].fragment, v.shares[0].password)
        val u2 = BurnPonyCryptoV2.unwrapShare(v.shares[1].fragment, v.shares[1].password)
        val rec = BurnPonyCryptoV2.sssCombine(listOf(Pair(u1.x, u1.share), Pair(u2.x, u2.share)))
        val env = BurnPonyCryptoV2.thresholdEncrypt(
            v.text, v.autoHideSeconds, rec,
            Base64Codec.decode(v.envelope.salt)!!, Base64Codec.decode(v.envelope.nonce)!!)
        assertEquals("ct", v.envelope.ct, env.ct)
        assertEquals(false, env.pw)
        assertEquals(2, env.v)
    }

    @Test
    fun decryptsThroughSharesAndPasswords() {
        val v = V2VectorSuite.threshold
        val payload = BurnPonyCryptoV2.thresholdDecrypt(v.envelope, listOf(
            Pair(v.shares[0].fragment, v.shares[0].password),
            Pair(v.shares[1].fragment, v.shares[1].password)))
        assertEquals(v.text, payload.text)
        assertEquals(v.autoHideSeconds, payload.autoHideSeconds)
    }

    @Test
    fun anyTSharesReconstruct() {
        val v = V2VectorSuite.threshold
        val payload = BurnPonyCryptoV2.thresholdDecrypt(v.envelope, listOf(
            Pair(v.shares[0].fragment, v.shares[0].password),
            Pair(v.shares[2].fragment, v.shares[2].password)))
        assertEquals(v.text, payload.text)
    }

    @Test
    fun insufficientSharesFail() {
        val v = V2VectorSuite.threshold
        assertThrows(BurnPonyCryptoException.KeyDerivationFailed::class.java) {
            BurnPonyCryptoV2.thresholdDecrypt(v.envelope, listOf(
                Pair(v.shares[0].fragment, v.shares[0].password)))
        }
    }

    @Test
    fun wrongSharePasswordFails() {
        val v = V2VectorSuite.threshold
        assertThrows(BurnPonyCryptoException.DecryptionFailed::class.java) {
            BurnPonyCryptoV2.unwrapShare(v.shares[0].fragment, "wrong")
        }
    }
}

class BurnPonyCryptoV2DecoyTest {

    @Test
    fun ciphertextReproducedByteExact() {
        val v = V2VectorSuite.decoy
        val slots = v.slots.map { Triple(it.password, it.text, it.autoHideSeconds) }
        val env = BurnPonyCryptoV2.decoyEncrypt(slots, v.fragmentKey, v.salt, v.slotNonces, emptyMap())
        assertEquals("decoy ct", v.envelope.ct, env.ct)
        assertEquals(true, env.pw)
        assertEquals(v.slotLen * V2VectorSuite.decoySlots, Base64Codec.decode(env.ct)!!.size)
    }

    @Test
    fun bothSlotsDecryptUnderTheirPassword() {
        val v = V2VectorSuite.decoy
        for (s in v.slots) {
            val r = BurnPonyCryptoV2.decoyDecrypt(v.envelope, v.fragmentKey, s.password)
            assertEquals("slot index", s.index, r.slotIndex)
            assertEquals("slot text", s.text, r.payload.text)
            assertEquals("slot ah", s.autoHideSeconds, r.payload.autoHideSeconds)
        }
    }

    @Test
    fun wrongPasswordAuthenticatesNoSlot() {
        val v = V2VectorSuite.decoy
        assertThrows(BurnPonyCryptoException.DecryptionFailed::class.java) {
            BurnPonyCryptoV2.decoyDecrypt(v.envelope, v.fragmentKey, "definitely-wrong")
        }
    }

    @Test
    fun plainPasswordNoteIsByteLengthIdenticalToDecoy() {
        val v = V2VectorSuite.decoy
        val slots = v.slots.map { Triple(it.password, it.text, it.autoHideSeconds) }
        val decoy = BurnPonyCryptoV2.decoyEncrypt(slots, v.fragmentKey, v.salt, v.slotNonces, emptyMap())
        val decoyLen = Base64Codec.decode(decoy.ct)!!.size
        val slotLen = decoyLen / V2VectorSuite.decoySlots
        val fill = (1 until V2VectorSuite.decoySlots).associateWith { ByteArray(slotLen) { 0x99.toByte() } }
        val plain = BurnPonyCryptoV2.decoyEncrypt(listOf(slots[0]), v.fragmentKey, v.salt,
            listOf(v.slotNonces[0]), fill)
        assertEquals(decoyLen, Base64Codec.decode(plain.ct)!!.size)
        assertEquals(decoy.pw, plain.pw)
        val r = BurnPonyCryptoV2.decoyDecrypt(plain, v.fragmentKey, slots[0].first)
        assertEquals(0, r.slotIndex)
        assertEquals(slots[0].second, r.payload.text)
    }
}
