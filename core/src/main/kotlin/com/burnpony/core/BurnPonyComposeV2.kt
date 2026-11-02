//
// BurnPonyComposeV2.kt
// App-facing helpers that build v2 notes with fresh random material, on top of
// the frozen BurnPonyCryptoV2 core. The core takes explicit salts/nonces so the
// cross-implementation vectors stay deterministic; these wrappers generate that
// material for real note creation. Pure JVM, no Android dependency.
//

package com.burnpony.core

import java.security.SecureRandom

object BurnPonyComposeV2 {

    private val rng = SecureRandom()

    fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        rng.nextBytes(bytes)
        return bytes
    }

    data class Compartment(val text: String, val password: String)
    data class DecoyNote(val envelope: SlottedEnvelope, val fragmentKey: ByteArray)
    data class ThresholdNote(val envelope: NoteEnvelope, val fragments: List<String>)

    /** A double-bottom note. `compartments` are decoy first, then real one(s);
     *  1..DECOY_SLOTS of them, each with its own passphrase. */
    fun decoy(compartments: List<Compartment>, autoHideSeconds: Int): DecoyNote {
        require(compartments.size in 1..BurnPonyCryptoV2.DECOY_SLOTS) { "1..${BurnPonyCryptoV2.DECOY_SLOTS} compartments" }
        val fragmentKey = randomBytes(32)
        val salt = randomBytes(16)
        val nonces = compartments.map { randomBytes(12) }
        val slots = compartments.map { Triple(it.password, it.text, autoHideSeconds) }
        val envelope = BurnPonyCryptoV2.decoyEncrypt(slots, fragmentKey, salt, nonces, emptyMap())
        return DecoyNote(envelope, fragmentKey)
    }

    /** A multi-reader threshold note. Returns the envelope plus one share
     *  fragment per key (n of them); any t reconstruct. `passwords` has one
     *  entry per key, null for an unwrapped key. */
    fun threshold(text: String, autoHideSeconds: Int, t: Int, n: Int, passwords: List<String?>): ThresholdNote {
        require(n >= 2 && t in 2..n && passwords.size == n) { "need 2 <= t <= n and one password slot per key" }
        val master = randomBytes(32)
        val salt = randomBytes(16)
        val nonce = randomBytes(12)
        val envelope = BurnPonyCryptoV2.thresholdEncrypt(text, autoHideSeconds, master, salt, nonce)
        // One fixed random polynomial per secret byte (t-1 coefficients each).
        val coeff = Array(master.size) { IntArray(t - 1) { rng.nextInt(256) } }
        val shares = BurnPonyCryptoV2.sssSplit(master, t, n, coeff)
        val fragments = ArrayList<String>(n)
        for (i in 0 until n) {
            val pw = passwords[i]
            val shareSalt = if (pw != null) randomBytes(16) else null
            val wrapNonce = if (pw != null) randomBytes(12) else null
            fragments.add(BurnPonyCryptoV2.wrapShare(shares[i].first, t, n, shares[i].second, pw, shareSalt, wrapNonce))
        }
        return ThresholdNote(envelope, fragments)
    }

    /** Share link for a threshold key fragment (no flag suffix). */
    fun shareLink(baseUrl: String, noteId: String, fragment: String): String {
        val trimmed = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        return "$trimmed/n/$noteId#$fragment"
    }
}
