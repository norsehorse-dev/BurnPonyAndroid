//
// BurnPonyCryptoV2.kt
// BurnPony core (Kotlin) — sender-side crypto for the envelope v2 wire format.
//
// Byte-compatible with the Python ground truth (shared/burnpony_v2_reference.py),
// the WebCrypto reference (shared/burnpony_v2_verify.mjs), and the Swift core
// (BurnPonyCryptoV2.swift), proven by shared/burnpony_v2_vectors.json.
// Status: FROZEN (Aug 27, 2026). Any format change from here means a new
// envelope version and a new vector suite, not an edit to these bytes.
//
// Covers the two crypto-enforced 2.0 features: multi-reader threshold unlock
// (Shamir over GF(2^8), AES field) and double-bottom / decoy notes (fixed
// 3-slot envelope). Reuses v1's canonicalPassword, PBKDF2, HKDF, MiniJson
// payload parse, and Base64Codec, so the two cores stay in lockstep. Adds no
// dependencies beyond the JDK.
//

package com.burnpony.core

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Slotted envelope for decoy notes: no server-visible nonce (each slot carries its own). */
data class SlottedEnvelope(
    val v: Int,
    val pw: Boolean,
    val salt: String,
    val ct: String,
)

object BurnPonyCryptoV2 {

    const val INFO_KEY = "BurnPony-v2-key"
    const val INFO_SLOT_PREFIX = "BurnPony-v2-slot"
    const val INFO_SHARE = "BurnPony-v2-share"
    const val DECOY_SLOTS = 3
    private const val KEY_LENGTH = 32
    private const val TAG_LENGTH_BITS = 128
    val SLOT_TIERS = intArrayOf(1024, 4096, 16384, 65536, 262144)

    data class ShareResult(val t: Int, val n: Int, val x: Int, val share: ByteArray)
    data class DecoyResult(val payload: NotePayload, val slotIndex: Int)

    // MARK: - Payload serialization (v:2)

    fun payloadJson(text: String, autoHideSeconds: Int): String =
        "{\"v\":2,\"t\":\"${BurnPonyCrypto.jsonEscape(text)}\",\"ah\":$autoHideSeconds}"

    /** Deterministic envelope JSON for slotted (decoy) notes, key order v,pw,salt,ct.
     *  The server stores it opaquely and the viewer parses it order-agnostically. */
    fun slottedEnvelopeJson(envelope: SlottedEnvelope): String =
        "{\"v\":${envelope.v},\"pw\":${envelope.pw}," +
            "\"salt\":\"${BurnPonyCrypto.jsonEscape(envelope.salt)}\"," +
            "\"ct\":\"${BurnPonyCrypto.jsonEscape(envelope.ct)}\"}"

    // MARK: - base64url (no padding)

    fun b64url(data: ByteArray): String =
        Base64Codec.encode(data).replace('+', '-').replace('/', '_').trimEnd('=')

    @Throws(BurnPonyCryptoException::class)
    fun b64urlDecode(s: String): ByteArray {
        val b = StringBuilder(s.replace('-', '+').replace('_', '/'))
        while (b.length % 4 != 0) b.append('=')
        return Base64Codec.decode(b.toString()) ?: throw BurnPonyCryptoException.BadEnvelope()
    }

    // MARK: - Primitives (reuse v1 so the two cores can never drift)

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray =
        BurnPonyCrypto.pbkdf2(BurnPonyCrypto.canonicalPassword(password), salt)

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray =
        BurnPonyCrypto.hkdfSha256(ikm, salt, info, KEY_LENGTH)

    private fun aesSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        return cipher.doFinal(plaintext) // ciphertext || tag, matching WebCrypto/JCA
    }

    @Throws(BurnPonyCryptoException::class)
    private fun aesOpen(key: ByteArray, nonce: ByteArray, ctTag: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
            cipher.doFinal(ctTag)
        } catch (e: Exception) {
            throw BurnPonyCryptoException.DecryptionFailed()
        }
    }

    // MARK: - Shamir Secret Sharing over GF(2^8), poly 0x11b, generator 3

    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    init {
        fun xtime(a: Int): Int { var b = a shl 1; if (b and 0x100 != 0) b = b xor 0x11b; return b and 0xff }
        var x = 1
        for (i in 0 until 255) { gfExp[i] = x; gfLog[x] = i; x = x xor xtime(x) } // multiply by 3
        for (i in 255 until 512) gfExp[i] = gfExp[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else gfExp[gfLog[a and 0xff] + gfLog[b and 0xff]]

    private fun gfInv(a: Int): Int = gfExp[255 - gfLog[a and 0xff]]

    /** Split into n shares, threshold t. coeff[j] holds the (t-1) coefficients for byte j. */
    fun sssSplit(secret: ByteArray, t: Int, n: Int, coeff: Array<IntArray>): List<Pair<Int, ByteArray>> {
        val shares = ArrayList<Pair<Int, ByteArray>>()
        for (x in 1..n) {
            val out = ByteArray(secret.size)
            for (j in secret.indices) {
                var acc = secret[j].toInt() and 0xff
                var xp = 1
                for (c in 0 until t - 1) { xp = gfMul(xp, x); acc = acc xor gfMul(coeff[j][c], xp) }
                out[j] = acc.toByte()
            }
            shares.add(Pair(x, out))
        }
        return shares
    }

    /** Reconstruct via Lagrange interpolation at x = 0. */
    fun sssCombine(shares: List<Pair<Int, ByteArray>>): ByteArray {
        val length = shares[0].second.size
        val secret = ByteArray(length)
        for (j in 0 until length) {
            var acc = 0
            for (i in shares.indices) {
                val xi = shares[i].first
                var num = 1
                var den = 1
                for (m in shares.indices) if (m != i) {
                    val xm = shares[m].first
                    num = gfMul(num, xm)
                    den = gfMul(den, xi xor xm)
                }
                acc = acc xor gfMul(shares[i].second[j].toInt() and 0xff, gfMul(num, gfInv(den)))
            }
            secret[j] = acc.toByte()
        }
        return secret
    }

    // MARK: - Feature A: threshold unlock

    @Throws(BurnPonyCryptoException::class)
    fun thresholdEncrypt(text: String, autoHideSeconds: Int, masterSecret: ByteArray, salt: ByteArray, nonce: ByteArray): NoteEnvelope {
        val key = hkdf(masterSecret, salt, INFO_KEY.toByteArray(Charsets.UTF_8))
        val payload = payloadJson(text, autoHideSeconds).toByteArray(Charsets.UTF_8)
        val ct = aesSeal(key, nonce, payload)
        return NoteEnvelope(
            v = 2, pw = false,
            salt = Base64Codec.encode(salt),
            nonce = Base64Codec.encode(nonce),
            ct = Base64Codec.encode(ct),
        )
    }

    /** Serialize one share into a URL-fragment record (base64url of a binary blob):
     *  magic(0x02) t n x flags [ shareSalt(16) wrapNonce(12) gcm(share) | share ]. */
    @Throws(BurnPonyCryptoException::class)
    fun wrapShare(x: Int, t: Int, n: Int, share: ByteArray, password: String?, shareSalt: ByteArray?, wrapNonce: ByteArray?): String {
        val header = byteArrayOf(0x02, t.toByte(), n.toByte(), x.toByte())
        val blob = if (password != null) {
            val ss = shareSalt ?: throw BurnPonyCryptoException.BadEnvelope()
            val wn = wrapNonce ?: throw BurnPonyCryptoException.BadEnvelope()
            val wkey = hkdf(pbkdf2(password, ss), ss, INFO_SHARE.toByteArray(Charsets.UTF_8))
            val wrapped = aesSeal(wkey, wn, share)
            header + byteArrayOf(0x01) + ss + wn + wrapped
        } else {
            header + byteArrayOf(0x00) + share
        }
        return b64url(blob)
    }

    @Throws(BurnPonyCryptoException::class)
    fun unwrapShare(fragment: String, password: String?): ShareResult {
        val blob = b64urlDecode(fragment)
        if (blob.size < 5 || blob[0].toInt() != 0x02) throw BurnPonyCryptoException.BadEnvelope()
        val t = blob[1].toInt() and 0xff
        val n = blob[2].toInt() and 0xff
        val x = blob[3].toInt() and 0xff
        val flags = blob[4].toInt()
        val body = blob.copyOfRange(5, blob.size)
        val share = if (flags and 0x01 != 0) {
            if (password == null || body.size < 28) throw BurnPonyCryptoException.DecryptionFailed()
            val ss = body.copyOfRange(0, 16)
            val wn = body.copyOfRange(16, 28)
            val wrapped = body.copyOfRange(28, body.size)
            val wkey = hkdf(pbkdf2(password, ss), ss, INFO_SHARE.toByteArray(Charsets.UTF_8))
            aesOpen(wkey, wn, wrapped)
        } else {
            body
        }
        return ShareResult(t, n, x, share)
    }

    @Throws(BurnPonyCryptoException::class)
    fun thresholdDecrypt(envelope: NoteEnvelope, shares: List<Pair<String, String?>>): NotePayload {
        val pairs = ArrayList<Pair<Int, ByteArray>>()
        var threshold = 0
        for ((frag, pw) in shares) {
            val u = unwrapShare(frag, pw)
            threshold = u.t
            pairs.add(Pair(u.x, u.share))
        }
        if (pairs.size < threshold) throw BurnPonyCryptoException.KeyDerivationFailed()
        val master = sssCombine(pairs.subList(0, threshold))
        val salt = Base64Codec.decode(envelope.salt) ?: throw BurnPonyCryptoException.BadEnvelope()
        val nonce = Base64Codec.decode(envelope.nonce) ?: throw BurnPonyCryptoException.BadEnvelope()
        val ct = Base64Codec.decode(envelope.ct) ?: throw BurnPonyCryptoException.BadEnvelope()
        val key = hkdf(master, salt, INFO_KEY.toByteArray(Charsets.UTF_8))
        return BurnPonyCrypto.parsePayload(aesOpen(key, nonce, ct))
    }

    // MARK: - Feature B: decoy notes

    @Throws(BurnPonyCryptoException::class)
    private fun pickTier(blockLen: Int): Int {
        for (tier in SLOT_TIERS) if (blockLen <= tier) return tier
        throw BurnPonyCryptoException.EncodingFailed()
    }

    private fun padBlock(payload: ByteArray, tier: Int): ByteArray {
        val block = ByteArray(tier)
        val n = payload.size
        block[0] = ((n ushr 24) and 0xff).toByte()
        block[1] = ((n ushr 16) and 0xff).toByte()
        block[2] = ((n ushr 8) and 0xff).toByte()
        block[3] = (n and 0xff).toByte()
        System.arraycopy(payload, 0, block, 4, payload.size)
        return block
    }

    private fun unpadBlock(block: ByteArray): ByteArray {
        val n = ((block[0].toInt() and 0xff) shl 24) or ((block[1].toInt() and 0xff) shl 16) or
            ((block[2].toInt() and 0xff) shl 8) or (block[3].toInt() and 0xff)
        return block.copyOfRange(4, 4 + n)
    }

    fun slotKey(fragmentKey: ByteArray, salt: ByteArray, password: String, index: Int): ByteArray {
        val ikm = fragmentKey + pbkdf2(password, salt)
        val info = INFO_SLOT_PREFIX.toByteArray(Charsets.UTF_8) + byteArrayOf(index.toByte())
        return hkdf(ikm, salt, info)
    }

    /** slots: (password, text, ah) per real compartment (1..DECOY_SLOTS). Unused slots come from
     *  fill[index] (whole-slot random bytes). All slots pad to one tier so the envelope reveals
     *  only the larger compartment's size, never that a second exists. */
    @Throws(BurnPonyCryptoException::class)
    fun decoyEncrypt(slots: List<Triple<String, String, Int>>, fragmentKey: ByteArray, salt: ByteArray, slotNonces: List<ByteArray>, fill: Map<Int, ByteArray>): SlottedEnvelope {
        var tier = 1
        for (i in 0 until DECOY_SLOTS) if (i < slots.size) {
            val pb = payloadJson(slots[i].second, slots[i].third).toByteArray(Charsets.UTF_8)
            tier = maxOf(tier, pickTier(4 + pb.size))
        }
        val slotLen = 12 + tier + 16
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until DECOY_SLOTS) {
            if (i < slots.size) {
                val pb = payloadJson(slots[i].second, slots[i].third).toByteArray(Charsets.UTF_8)
                val block = padBlock(pb, tier)
                val key = slotKey(fragmentKey, salt, slots[i].first, i)
                out.write(slotNonces[i])
                out.write(aesSeal(key, slotNonces[i], block))
            } else {
                // Unused slot: caller-supplied fill keeps vectors deterministic;
                // otherwise random bytes indistinguishable from a real slot.
                val f = fill[i] ?: ByteArray(slotLen).also { java.security.SecureRandom().nextBytes(it) }
                out.write(f.copyOfRange(0, minOf(slotLen, f.size)))
            }
        }
        return SlottedEnvelope(v = 2, pw = true, salt = Base64Codec.encode(salt), ct = Base64Codec.encode(out.toByteArray()))
    }

    @Throws(BurnPonyCryptoException::class)
    fun decoyDecrypt(envelope: SlottedEnvelope, fragmentKey: ByteArray, password: String): DecoyResult {
        val ct = Base64Codec.decode(envelope.ct) ?: throw BurnPonyCryptoException.BadEnvelope()
        val salt = Base64Codec.decode(envelope.salt) ?: throw BurnPonyCryptoException.BadEnvelope()
        val slotLen = ct.size / DECOY_SLOTS
        for (i in 0 until DECOY_SLOTS) {
            val slot = ct.copyOfRange(i * slotLen, (i + 1) * slotLen)
            val nonce = slot.copyOfRange(0, 12)
            val body = slot.copyOfRange(12, slot.size)
            val key = slotKey(fragmentKey, salt, password, i)
            try {
                return DecoyResult(BurnPonyCrypto.parsePayload(unpadBlock(aesOpen(key, nonce, body))), i)
            } catch (e: BurnPonyCryptoException.DecryptionFailed) {
                // wrong password for this slot; try the next
            }
        }
        throw BurnPonyCryptoException.DecryptionFailed()
    }
}
