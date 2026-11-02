//
// BurnPonyComposeV2Test.kt
// Round-trips the app-facing v2 compose helpers (which generate random
// material) back through the frozen decrypt path.
//

package com.burnpony.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnPonyComposeV2DecoyTest {

    @Test
    fun decoyRoundTripsEachCompartment() {
        val d = BurnPonyComposeV2.decoy(
            listOf(BurnPonyComposeV2.Compartment("grocery decoy", "d1"),
                   BurnPonyComposeV2.Compartment("the real secret", "d2")), 0)
        val r0 = BurnPonyCryptoV2.decoyDecrypt(d.envelope, d.fragmentKey, "d1")
        assertEquals(0, r0.slotIndex)
        assertEquals("grocery decoy", r0.payload.text)
        val r1 = BurnPonyCryptoV2.decoyDecrypt(d.envelope, d.fragmentKey, "d2")
        assertEquals(1, r1.slotIndex)
        assertEquals("the real secret", r1.payload.text)
    }

    @Test
    fun decoyWrongPasswordRevealsNothing() {
        val d = BurnPonyComposeV2.decoy(
            listOf(BurnPonyComposeV2.Compartment("a", "p1"),
                   BurnPonyComposeV2.Compartment("b", "p2")), 0)
        assertThrows(BurnPonyCryptoException.DecryptionFailed::class.java) {
            BurnPonyCryptoV2.decoyDecrypt(d.envelope, d.fragmentKey, "wrong")
        }
    }

    @Test
    fun decoyIsAlwaysFullSlotCount() {
        val d = BurnPonyComposeV2.decoy(
            listOf(BurnPonyComposeV2.Compartment("x", "p1"),
                   BurnPonyComposeV2.Compartment("y", "p2")), 0)
        assertTrue(d.envelope.pw)
        assertEquals(0, Base64Codec.decode(d.envelope.ct)!!.size % BurnPonyCryptoV2.DECOY_SLOTS)
    }
}

class BurnPonyComposeV2ThresholdTest {

    @Test
    fun anyTOfNReconstruct() {
        val th = BurnPonyComposeV2.threshold("split note", 0, 2, 3, listOf("k1", "k2", null))
        assertEquals(3, th.fragments.size)
        assertEquals("split note", BurnPonyCryptoV2.thresholdDecrypt(
            th.envelope, listOf(Pair(th.fragments[0], "k1"), Pair(th.fragments[2], null))).text)
        assertEquals("split note", BurnPonyCryptoV2.thresholdDecrypt(
            th.envelope, listOf(Pair(th.fragments[1], "k2"), Pair(th.fragments[2], null))).text)
    }

    @Test
    fun insufficientSharesFail() {
        val th = BurnPonyComposeV2.threshold("n", 0, 2, 3, listOf(null, null, null))
        assertThrows(BurnPonyCryptoException.KeyDerivationFailed::class.java) {
            BurnPonyCryptoV2.thresholdDecrypt(th.envelope, listOf(Pair(th.fragments[0], null)))
        }
    }

    @Test
    fun wrongKeyPasswordFails() {
        val th = BurnPonyComposeV2.threshold("n", 0, 2, 2, listOf("k1", "k2"))
        assertThrows(BurnPonyCryptoException.DecryptionFailed::class.java) {
            BurnPonyCryptoV2.unwrapShare(th.fragments[0], "wrong")
        }
    }
}
