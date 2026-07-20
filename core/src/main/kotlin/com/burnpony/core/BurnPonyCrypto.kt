//
// BurnPonyCrypto.kt
// BurnPony core (Kotlin) — sender-side crypto for the BurnPony v1 wire format.
//
// This module is byte-compatible with the shipped Swift/CryptoKit core, the
// WebCrypto viewer reference (shared/burnpony_crypto.js), and the canonical
// cross-implementation vectors (shared/burnpony_vectors.json). Any change here
// requires regenerating and re-proving the vectors on every side.
//
// Zero dependencies beyond the JDK/Android platform: AES-GCM and PBKDF2 come
// from JCA, HKDF is hand-rolled per RFC 5869, base64 and the payload
// serializer are implemented here so the core is auditable end to end and
// safe on every supported API level (java.util.Base64 needs API 26; this
// module supports minSdk 24 without desugaring).
//

package com.burnpony.core

import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BurnPonyLimits {
    const val MAX_NOTE_CHARACTERS = 50_000
}

/** Decrypted note payload: {"v":1,"t":"...","ah":n} */
data class NotePayload(
    val v: Int,
    val t: String,
    val ah: Int,
) {
    val text: String get() = t
    val autoHideSeconds: Int get() = ah
}

/** The opaque envelope stored by the server: {"v":1,"pw":bool,"salt":b64,"nonce":b64,"ct":b64} */
data class NoteEnvelope(
    val v: Int,
    val pw: Boolean,
    val salt: String,
    val nonce: String,
    val ct: String,
)

sealed class BurnPonyCryptoException(message: String) : Exception(message) {
    class BadFragmentKey : BurnPonyCryptoException("bad fragment key")
    class BadEnvelope : BurnPonyCryptoException("bad envelope")
    class UnsupportedVersion : BurnPonyCryptoException("unsupported version")
    class KeyDerivationFailed : BurnPonyCryptoException("key derivation failed")
    class DecryptionFailed : BurnPonyCryptoException("decryption failed")
    class EncodingFailed : BurnPonyCryptoException("encoding failed")
}

object BurnPonyCrypto {

    const val INFO_STRING = "BurnPony-v1-key"
    const val PBKDF2_ITERATIONS = 600_000
    const val KEY_LENGTH = 32
    const val SALT_LENGTH = 16
    const val NONCE_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    private val secureRandom = SecureRandom()

    // MARK: - Random material

    fun generateFragmentKey(): ByteArray = randomBytes(KEY_LENGTH)

    fun generateSalt(): ByteArray = randomBytes(SALT_LENGTH)

    fun generateNonce(): ByteArray = randomBytes(NONCE_LENGTH)

    private fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    // MARK: - Fragment key encoding (base64url, no padding)

    fun fragmentEncode(key: ByteArray): String =
        Base64Codec.encode(key)
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')

    @Throws(BurnPonyCryptoException::class)
    fun fragmentDecode(fragment: String): ByteArray {
        val builder = StringBuilder(
            fragment.replace('-', '+').replace('_', '/')
        )
        while (builder.length % 4 != 0) builder.append('=')
        val key = Base64Codec.decode(builder.toString())
            ?: throw BurnPonyCryptoException.BadFragmentKey()
        if (key.size != KEY_LENGTH) throw BurnPonyCryptoException.BadFragmentKey()
        return key
    }

    // MARK: - Key derivation

    // Passphrases are canonicalized before derivation: Unicode NFC
    // normalization plus trimming of leading and trailing whitespace and
    // U+FEFF. This must match bpCanonicalPassword in the WebCrypto core
    // (regex /^[\s﻿]+|[\s﻿]+$/g) so a passphrase typed with a stray
    // keyboard space or in decomposed form still unlocks the note.
    // Capitalization and interior spacing are deliberately not forgiven.
    //
    // The trim set is spelled out explicitly (the JavaScript \s class plus
    // U+FEFF) instead of relying on Character.isWhitespace, whose membership
    // differs from JS \s on U+00A0 and the U+001C-U+001F separators.
    fun canonicalPassword(password: String): String {
        val nfc = Normalizer.normalize(password, Normalizer.Form.NFC)
        var start = 0
        var end = nfc.length
        while (start < end && isTrimmable(nfc[start])) start++
        while (end > start && isTrimmable(nfc[end - 1])) end--
        return nfc.substring(start, end)
    }

    private fun isTrimmable(c: Char): Boolean = when (c.code) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, // tab, LF, VT, FF, CR
        0x20, // space
        0xA0, // no-break space
        0x1680, // ogham space mark
        in 0x2000..0x200A, // en quad through hair space
        0x2028, 0x2029, // line separator, paragraph separator
        0x202F, 0x205F, // narrow no-break space, medium mathematical space
        0x3000, // ideographic space
        0xFEFF, // zero width no-break space, the BOM
        -> true
        else -> false
    }

    /**
     * Derives the 32-byte AES key.
     *
     * Without passphrase: HKDF-SHA256(IKM = fragmentKey, salt, info, 32).
     * With passphrase: P = PBKDF2-HMAC-SHA256(canonical(passphrase), salt,
     * 600000, 32); IKM = fragmentKey || P; then HKDF as above.
     *
     * The caller may pass the raw passphrase; canonicalization happens here.
     */
    @Throws(BurnPonyCryptoException::class)
    fun deriveKey(fragmentKey: ByteArray, salt: ByteArray, password: String?): ByteArray {
        if (fragmentKey.size != KEY_LENGTH) throw BurnPonyCryptoException.BadFragmentKey()
        val ikm: ByteArray = if (password != null) {
            val canonical = canonicalPassword(password)
            // A passphrase that canonicalizes to empty is rejected here, not
            // just in the UI: the platform SecretKeyFactory would derive from
            // an empty password while the API 24/25 fallback cannot (HMAC key
            // must be non-empty), and the core must behave identically on
            // every API level. pw=true with an effectively-empty passphrase
            // is always a caller bug.
            if (canonical.isEmpty()) throw BurnPonyCryptoException.KeyDerivationFailed()
            fragmentKey + pbkdf2(canonical, salt)
        } else {
            fragmentKey
        }
        return hkdfSha256(ikm, salt, INFO_STRING.toByteArray(Charsets.UTF_8), KEY_LENGTH)
    }

    // PBKDF2-HMAC-SHA256. The platform SecretKeyFactory is native and fast
    // but "PBKDF2WithHmacSHA256" only exists from API 26; on API 24/25 the
    // pure-JVM implementation below is used. Both paths are proven equal and
    // vector-exact by the unit test suite.
    internal fun pbkdf2(canonicalPassword: String, salt: ByteArray): ByteArray {
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(
                canonicalPassword.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH * 8
            )
            factory.generateSecret(spec).encoded
        } catch (e: Exception) {
            pbkdf2Fallback(canonicalPassword.toByteArray(Charsets.UTF_8), salt)
        }
    }

    // RFC 2898 PBKDF2 with HMAC-SHA256, output length 32 = exactly one block.
    internal fun pbkdf2Fallback(passwordUtf8: ByteArray, salt: ByteArray): ByteArray {
        if (passwordUtf8.isEmpty()) throw BurnPonyCryptoException.KeyDerivationFailed()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passwordUtf8, "HmacSHA256"))
        // U1 = HMAC(password, salt || INT(1))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val result = u.copyOf()
        for (i in 2..PBKDF2_ITERATIONS) {
            u = mac.doFinal(u)
            for (j in result.indices) {
                result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return result
    }

    // RFC 5869 HKDF-SHA256, extract + expand. Output 32 bytes = one block.
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // Extract: PRK = HMAC-Hash(salt, IKM)
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // Expand
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(length)
        var previous = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val toCopy = minOf(previous.size, length - generated)
            System.arraycopy(previous, 0, okm, generated, toCopy)
            generated += toCopy
            counter++
        }
        return okm
    }

    // MARK: - Payload serialization
    // Deterministic, byte-compatible with JSON.stringify({v:1,t:text,ah:n})
    // in the viewer, the Swift hand-rolled serializer, and the Python vector
    // generator. kotlinx.serialization / Moshi default output would NOT
    // match, which is why this is hand-rolled.

    fun payloadJson(text: String, autoHideSeconds: Int): String =
        "{\"v\":1,\"t\":\"${jsonEscape(text)}\",\"ah\":$autoHideSeconds}"

    /** Deterministic envelope JSON, key order v,pw,salt,nonce,ct. */
    fun envelopeJson(envelope: NoteEnvelope): String =
        "{\"v\":${envelope.v},\"pw\":${envelope.pw}," +
            "\"salt\":\"${jsonEscape(envelope.salt)}\"," +
            "\"nonce\":\"${jsonEscape(envelope.nonce)}\"," +
            "\"ct\":\"${jsonEscape(envelope.ct)}\"}"

    internal fun jsonEscape(s: String): String {
        val out = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\u0008' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        out.append(String.format("\\u%04x", c.code))
                    } else {
                        out.append(c)
                    }
                }
            }
        }
        return out.toString()
    }

    // MARK: - Encrypt

    /** Encrypts with fresh random material; returns the envelope and the fragment key. */
    @Throws(BurnPonyCryptoException::class)
    fun encryptNote(text: String, autoHideSeconds: Int, password: String?): Pair<NoteEnvelope, ByteArray> {
        val key = generateFragmentKey()
        val envelope = encryptNote(
            text = text,
            autoHideSeconds = autoHideSeconds,
            fragmentKey = key,
            salt = generateSalt(),
            nonce = generateNonce(),
            password = password,
        )
        return Pair(envelope, key)
    }

    /** Deterministic form used by the vector tests. */
    @Throws(BurnPonyCryptoException::class)
    fun encryptNote(
        text: String,
        autoHideSeconds: Int,
        fragmentKey: ByteArray,
        salt: ByteArray,
        nonce: ByteArray,
        password: String?,
    ): NoteEnvelope {
        if (text.length > BurnPonyLimits.MAX_NOTE_CHARACTERS) {
            throw BurnPonyCryptoException.EncodingFailed()
        }
        if (salt.size != SALT_LENGTH || nonce.size != NONCE_LENGTH) {
            throw BurnPonyCryptoException.BadEnvelope()
        }
        val payload = payloadJson(text, autoHideSeconds).toByteArray(Charsets.UTF_8)
        val key = deriveKey(fragmentKey, salt, password)
        val ct: ByteArray = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_LENGTH_BITS, nonce),
            )
            cipher.doFinal(payload) // JCA output is ciphertext || tag, matching WebCrypto
        } catch (e: Exception) {
            throw BurnPonyCryptoException.EncodingFailed()
        }
        return NoteEnvelope(
            v = 1,
            pw = password != null,
            salt = Base64Codec.encode(salt),
            nonce = Base64Codec.encode(nonce),
            ct = Base64Codec.encode(ct),
        )
    }

    // MARK: - Decrypt

    @Throws(BurnPonyCryptoException::class)
    fun decryptNote(envelope: NoteEnvelope, fragmentKey: ByteArray, password: String?): NotePayload {
        if (envelope.v != 1) throw BurnPonyCryptoException.UnsupportedVersion()
        val salt = Base64Codec.decode(envelope.salt)
        val nonce = Base64Codec.decode(envelope.nonce)
        val ct = Base64Codec.decode(envelope.ct)
        if (salt == null || salt.size != SALT_LENGTH ||
            nonce == null || nonce.size != NONCE_LENGTH ||
            ct == null || ct.size < 16
        ) {
            throw BurnPonyCryptoException.BadEnvelope()
        }
        val key = deriveKey(fragmentKey, salt, if (envelope.pw) password else null)
        val plaintext: ByteArray = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_LENGTH_BITS, nonce),
            )
            cipher.doFinal(ct)
        } catch (e: Exception) {
            throw BurnPonyCryptoException.DecryptionFailed()
        }
        val payload = parsePayload(plaintext)
        if (payload.v != 1) throw BurnPonyCryptoException.UnsupportedVersion()
        return payload
    }

    /** Order-agnostic payload decode (normal JSON parse), like JSONDecoder on iOS. */
    @Throws(BurnPonyCryptoException::class)
    internal fun parsePayload(plaintext: ByteArray): NotePayload {
        val obj = try {
            MiniJson.parseObject(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BurnPonyCryptoException.BadEnvelope()
        }
        val v = (obj["v"] as? Number)?.toInt() ?: throw BurnPonyCryptoException.BadEnvelope()
        val t = obj["t"] as? String ?: throw BurnPonyCryptoException.BadEnvelope()
        val ah = (obj["ah"] as? Number)?.toInt() ?: throw BurnPonyCryptoException.BadEnvelope()
        return NotePayload(v = v, t = t, ah = ah)
    }

    /** Order-agnostic envelope decode for tests and future use. */
    @Throws(BurnPonyCryptoException::class)
    fun parseEnvelope(json: String): NoteEnvelope {
        val obj = try {
            MiniJson.parseObject(json)
        } catch (e: Exception) {
            throw BurnPonyCryptoException.BadEnvelope()
        }
        val v = (obj["v"] as? Number)?.toInt() ?: throw BurnPonyCryptoException.BadEnvelope()
        val pw = obj["pw"] as? Boolean ?: throw BurnPonyCryptoException.BadEnvelope()
        val salt = obj["salt"] as? String ?: throw BurnPonyCryptoException.BadEnvelope()
        val nonce = obj["nonce"] as? String ?: throw BurnPonyCryptoException.BadEnvelope()
        val ct = obj["ct"] as? String ?: throw BurnPonyCryptoException.BadEnvelope()
        return NoteEnvelope(v = v, pw = pw, salt = salt, nonce = nonce, ct = ct)
    }

    // MARK: - Share link assembly
    // Fragment flags: .p passphrase set, .r receipts on, combined .pr. Flags
    // live in the fragment so the server never sees them; they let the viewer
    // show the passphrase field and receipt disclosure before the
    // view-consuming reveal tap.

    fun shareLink(baseUrl: String, noteId: String, fragmentKey: ByteArray): String =
        shareLink(baseUrl, noteId, fragmentKey, hasPassword = false, hasReceipt = false)

    fun shareLink(
        baseUrl: String,
        noteId: String,
        fragmentKey: ByteArray,
        hasPassword: Boolean,
        hasReceipt: Boolean,
    ): String {
        var flags = ""
        if (hasPassword) flags += "p"
        if (hasReceipt) flags += "r"
        val suffix = if (flags.isEmpty()) "" else ".$flags"
        return "$baseUrl/n/$noteId#${fragmentEncode(fragmentKey)}$suffix"
    }
}
