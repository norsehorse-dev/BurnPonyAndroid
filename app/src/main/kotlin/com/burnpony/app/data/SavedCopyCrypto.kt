//
// SavedCopyCrypto.kt
// Opt-in saved copies of sent notes, locked by the Android Keystore itself
// rather than by an app-level gate.
//
// Key: an RSA-2048 key pair in the AndroidKeyStore (alias
// "burnpony_saved_copies_rsa"), StrongBox-backed where available. The
// private key requires user authentication:
//   API 30+  : every decrypt needs a fresh BiometricPrompt CryptoObject auth
//              (strong biometric or screen lock).
//   API 24-29: per-use CryptoObject auth cannot use the screen lock, so the
//              key unlocks for 10 seconds after a BiometricPrompt auth and
//              the copy is opened inside that window.
// Adding a fingerprint does not invalidate the key (matches iOS
// .userPresence). Removing the screen lock destroys it, and with it every
// copy. The private key never leaves the secure hardware and is never
// backed up (allowBackup is off; Keystore keys are not exportable).
//
// Hybrid sealing, so saving needs no authentication: each copy gets a fresh
// AES-256 key and 12-byte nonce (AES/GCM, note id as AAD), and the AES key
// is wrapped with the public key (RSA OAEP, SHA-256, MGF1 SHA-1, the
// Keystore-compatible parameters). The public key is re-materialized as a
// plain software key so the Keystore provider never handles the encrypt.
//
// Stored form: Base64(version(1) || wrappedKeyLen(2, big-endian) ||
// wrappedKey || nonce(12) || ciphertext || tag(16)).
//

package com.burnpony.app.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** A saved copy could not be opened on this device (key gone, not unlocked, data damaged). */
class SavedCopyUnavailableException(cause: Throwable? = null) : Exception(cause)

/** Device seam for saved copies; the Keystore implementation is below. */
interface SavedCopyVault {
    /**
     * Makes sure a usable key pair exists, creating one (or replacing one the
     * system invalidated). Throws when the device has no secure lock screen.
     */
    fun ensureKey()

    /** Seals [plaintext] for [noteId]. Needs no user authentication. */
    fun seal(plaintext: String, noteId: String): String

    /**
     * A decrypt cipher on the private key. On API 30+ hand it to
     * BiometricPrompt as a CryptoObject; on API 24-29 call this only after
     * the user has authenticated. Throws [SavedCopyUnavailableException].
     */
    fun unlockCipher(): Cipher

    /** Opens [stored] with an authenticated [unlockCipher]. Throws [SavedCopyUnavailableException]. */
    fun open(stored: String, noteId: String, unlockCipher: Cipher): String
}

/**
 * The byte format and the hybrid seal/open, free of Android APIs so JVM unit
 * tests can run it against a software RSA key pair.
 */
internal object SavedCopyFormat {
    const val VERSION: Int = 1
    const val AES_KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HEADER_BYTES = 3

    /** OAEP SHA-256 with MGF1 SHA-1: the combination the Keystore accepts everywhere. */
    fun oaepSpec(): OAEPParameterSpec =
        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    fun seal(
        plaintext: String,
        noteId: String,
        publicKey: PublicKey,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val aesKey = ByteArray(AES_KEY_BYTES).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        try {
            val wrap = Cipher.getInstance(RSA_TRANSFORMATION)
            wrap.init(Cipher.ENCRYPT_MODE, publicKey, oaepSpec())
            val wrappedKey = wrap.doFinal(aesKey)
            check(wrappedKey.size <= 0xFFFF) { "wrapped key too long" }

            val gcm = Cipher.getInstance(AES_TRANSFORMATION)
            gcm.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(TAG_BYTES * 8, nonce),
            )
            gcm.updateAAD(noteId.toByteArray(Charsets.UTF_8))
            val sealed = gcm.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val out = ByteArrayOutputStream(HEADER_BYTES + wrappedKey.size + NONCE_BYTES + sealed.size)
            out.write(VERSION)
            out.write((wrappedKey.size ushr 8) and 0xFF)
            out.write(wrappedKey.size and 0xFF)
            out.write(wrappedKey)
            out.write(nonce)
            out.write(sealed)
            return out.toByteArray()
        } finally {
            aesKey.fill(0)
        }
    }

    /**
     * [unlockCipher] is an RSA decrypt cipher already initialized with the
     * private key and [oaepSpec]. Any failure (format, unwrap, tag, the
     * Keystore refusing the key) becomes [SavedCopyUnavailableException].
     */
    fun open(blob: ByteArray, noteId: String, unlockCipher: Cipher): String {
        try {
            if (blob.size < HEADER_BYTES) throw SavedCopyUnavailableException()
            if ((blob[0].toInt() and 0xFF) != VERSION) throw SavedCopyUnavailableException()
            val wrappedLen = ((blob[1].toInt() and 0xFF) shl 8) or (blob[2].toInt() and 0xFF)
            val wrappedEnd = HEADER_BYTES + wrappedLen
            if (wrappedLen == 0 || blob.size < wrappedEnd + NONCE_BYTES + TAG_BYTES) {
                throw SavedCopyUnavailableException()
            }
            val wrappedKey = blob.copyOfRange(HEADER_BYTES, wrappedEnd)
            val nonce = blob.copyOfRange(wrappedEnd, wrappedEnd + NONCE_BYTES)
            val sealed = blob.copyOfRange(wrappedEnd + NONCE_BYTES, blob.size)

            val aesKey = unlockCipher.doFinal(wrappedKey)
            try {
                if (aesKey.size != AES_KEY_BYTES) throw SavedCopyUnavailableException()
                val gcm = Cipher.getInstance(AES_TRANSFORMATION)
                gcm.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(aesKey, "AES"),
                    GCMParameterSpec(TAG_BYTES * 8, nonce),
                )
                gcm.updateAAD(noteId.toByteArray(Charsets.UTF_8))
                return String(gcm.doFinal(sealed), Charsets.UTF_8)
            } finally {
                aesKey.fill(0)
            }
        } catch (e: SavedCopyUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw SavedCopyUnavailableException(e)
        }
    }

    /** RSA decrypt cipher for [privateKey] with the matching OAEP parameters. */
    fun unwrapCipher(privateKey: PrivateKey): Cipher =
        Cipher.getInstance(RSA_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, privateKey, oaepSpec())
        }
}

class KeystoreSavedCopyVault : SavedCopyVault {

    override fun ensureKey() {
        val keyStore = keyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) {
            if (isUsable(keyStore)) return
            // Invalidated (for example the screen lock was removed and set
            // again): copies sealed to it are gone either way.
            keyStore.deleteEntry(KEY_ALIAS)
        }
        createKeyPair()
    }

    override fun seal(plaintext: String, noteId: String): String {
        ensureKey()
        val blob = SavedCopyFormat.seal(plaintext, noteId, softwarePublicKey())
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    override fun unlockCipher(): Cipher {
        try {
            val privateKey = keyStore().getKey(KEY_ALIAS, null) as? PrivateKey
                ?: throw SavedCopyUnavailableException()
            return SavedCopyFormat.unwrapCipher(privateKey)
        } catch (e: SavedCopyUnavailableException) {
            throw e
        } catch (e: Exception) {
            // KeyPermanentlyInvalidatedException, UserNotAuthenticatedException,
            // Keystore errors.
            throw SavedCopyUnavailableException(e)
        }
    }

    override fun open(stored: String, noteId: String, unlockCipher: Cipher): String {
        val blob = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw SavedCopyUnavailableException(e)
        }
        return SavedCopyFormat.open(blob, noteId, unlockCipher)
    }

    /**
     * Initializing a decrypt cipher tells a valid key from an invalidated
     * one without any authentication: API 30+ per-use keys init fine and
     * ask at doFinal; API 24-29 timed keys throw UserNotAuthenticated when
     * locked, which still means the key is valid.
     */
    private fun isUsable(keyStore: KeyStore): Boolean {
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey ?: return false
        if (keyStore.getCertificate(KEY_ALIAS) == null) return false
        return try {
            SavedCopyFormat.unwrapCipher(privateKey)
            true
        } catch (e: KeyPermanentlyInvalidatedException) {
            false
        } catch (e: UserNotAuthenticatedException) {
            true
        }
    }

    /** The public half as a plain software key, so encrypting never touches Keystore auth rules. */
    private fun softwarePublicKey(): PublicKey {
        val certificate = keyStore().getCertificate(KEY_ALIAS)
            ?: throw IllegalStateException("saved-copy key missing")
        return KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(certificate.publicKey.encoded))
    }

    private fun createKeyPair() {
        if (Build.VERSION.SDK_INT >= 28 && StrongBoxKeys.tryGenerate()) return
        generate(specBuilder().build())
    }

    // The API 28 class (StrongBoxUnavailableException) is referenced only
    // from this object, which is never loaded below API 28.
    @RequiresApi(28)
    private object StrongBoxKeys {
        fun tryGenerate(): Boolean = try {
            generate(specBuilder().setIsStrongBoxBacked(true).build())
            true
        } catch (e: StrongBoxUnavailableException) {
            // No StrongBox on this device: use the TEE-backed Keystore.
            false
        } catch (e: Exception) {
            // Any other StrongBox refusal also falls back. A real problem
            // (no secure lock screen) fails again in the TEE attempt and is
            // reported from there.
            false
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "burnpony_saved_copies_rsa"

        /** Seconds the key stays unlocked after an auth on API 24-29. */
        const val LEGACY_AUTH_WINDOW_SECONDS = 10

        fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        fun specBuilder(): KeyGenParameterSpec.Builder {
            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(false)
            if (Build.VERSION.SDK_INT >= 30) {
                // Timeout 0: every decrypt needs its own CryptoObject auth.
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                setLegacyAuthWindow(builder)
            }
            return builder
        }

        @Suppress("DEPRECATION")
        fun setLegacyAuthWindow(builder: KeyGenParameterSpec.Builder) {
            builder.setUserAuthenticationValidityDurationSeconds(LEGACY_AUTH_WINDOW_SECONDS)
        }

        /** Throws (InvalidAlgorithmParameterException) when there is no secure lock screen. */
        fun generate(spec: KeyGenParameterSpec) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            generator.initialize(spec)
            generator.generateKeyPair()
        }
    }
}
