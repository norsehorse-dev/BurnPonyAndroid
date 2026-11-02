//
// SavedCopyFormatTest.kt
// The saved-copy hybrid format (RSA-OAEP wrapped AES-256-GCM key) against a
// software RSA key pair. On device the private key lives in the Keystore;
// the framing, wrap parameters and AAD binding are the same.
//

package com.burnpony.app

import com.burnpony.app.data.SavedCopyFormat
import com.burnpony.app.data.SavedCopyUnavailableException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator

class SavedCopyFormatTest {

    private fun open(blob: ByteArray, noteId: String, keys: KeyPair = KEYS): String =
        SavedCopyFormat.open(blob, noteId, SavedCopyFormat.unwrapCipher(keys.private))

    private fun assertUnavailable(block: () -> Unit) {
        try {
            block()
        } catch (e: SavedCopyUnavailableException) {
            return
        }
        throw AssertionError("expected SavedCopyUnavailableException")
    }

    @Test
    fun roundTrips() {
        for (text in listOf("hunter2", "", "Grüße, 你好, emoji 🔥", "x".repeat(10_000))) {
            val blob = SavedCopyFormat.seal(text, "note-1", KEYS.public)
            assertEquals(text, open(blob, "note-1"))
        }
    }

    @Test
    fun layoutIsVersionLengthWrappedNonceCiphertext() {
        val text = "hello"
        val blob = SavedCopyFormat.seal(text, "abc", KEYS.public)
        assertEquals(1, blob[0].toInt())
        val wrappedLen = ((blob[1].toInt() and 0xFF) shl 8) or (blob[2].toInt() and 0xFF)
        assertEquals(256, wrappedLen) // RSA-2048
        val expected = 3 + wrappedLen + SavedCopyFormat.NONCE_BYTES +
            text.toByteArray(Charsets.UTF_8).size + SavedCopyFormat.TAG_BYTES
        assertEquals(expected, blob.size)
    }

    @Test
    fun everySealUsesFreshKeyAndNonce() {
        val a = SavedCopyFormat.seal("same", "id", KEYS.public)
        val b = SavedCopyFormat.seal("same", "id", KEYS.public)
        assertFalse(a.contentEquals(b))
        // Nonces differ too.
        val nonceA = a.copyOfRange(3 + 256, 3 + 256 + 12)
        val nonceB = b.copyOfRange(3 + 256, 3 + 256 + 12)
        assertFalse(nonceA.contentEquals(nonceB))
    }

    @Test
    fun noteIdIsBoundAsAad() {
        val blob = SavedCopyFormat.seal("secret", "note-A", KEYS.public)
        assertUnavailable { open(blob, "note-B") }
    }

    @Test
    fun tamperedCiphertextFails() {
        val blob = SavedCopyFormat.seal("secret", "id", KEYS.public)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        assertUnavailable { open(blob, "id") }
    }

    @Test
    fun tamperedWrappedKeyFails() {
        val blob = SavedCopyFormat.seal("secret", "id", KEYS.public)
        blob[10] = (blob[10].toInt() xor 0x01).toByte()
        assertUnavailable { open(blob, "id") }
    }

    @Test
    fun otherKeyPairCannotOpen() {
        val blob = SavedCopyFormat.seal("secret", "id", KEYS.public)
        assertUnavailable { open(blob, "id", OTHER_KEYS) }
    }

    @Test
    fun unknownVersionAndTruncationFail() {
        val blob = SavedCopyFormat.seal("secret", "id", KEYS.public)
        val badVersion = blob.copyOf().also { it[0] = 2 }
        assertUnavailable { open(badVersion, "id") }
        assertUnavailable { open(blob.copyOf(3 + 256 + 12), "id") }
        assertUnavailable { open(ByteArray(0), "id") }
        assertUnavailable { open(byteArrayOf(1, 0, 0), "id") }
    }

    @Test
    fun oaepParametersAreKeystoreCompatible() {
        val spec = SavedCopyFormat.oaepSpec()
        assertEquals("SHA-256", spec.digestAlgorithm)
        assertEquals("MGF1", spec.mgfAlgorithm)
        assertEquals(
            "SHA-1",
            (spec.mgfParameters as java.security.spec.MGF1ParameterSpec).digestAlgorithm,
        )
        assertArrayEquals(
            ByteArray(0),
            (spec.pSource as javax.crypto.spec.PSource.PSpecified).value,
        )
    }

    private companion object {
        val KEYS: KeyPair by lazy { rsa() }
        val OTHER_KEYS: KeyPair by lazy { rsa() }

        fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
