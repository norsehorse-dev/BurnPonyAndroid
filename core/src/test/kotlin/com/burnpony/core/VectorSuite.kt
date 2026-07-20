//
// VectorSuite.kt
// Loads the canonical cross-implementation vectors
// (shared/burnpony_vectors.json, copied into test resources) for the
// Phase 1 gate: 100% vector parity before anything else ships.
//

package com.burnpony.core

data class Vector(
    val name: String,
    val keyFragment: String,
    val password: String?,
    val salt: ByteArray,
    val nonce: ByteArray,
    val derivedKey: ByteArray,
    val ciphertext: ByteArray,
    val plaintextPayload: String,
    val text: String,
    val autoHideSeconds: Int,
    val envelopeJson: String,
)

object VectorSuite {

    val vectors: List<Vector> by lazy { load() }

    private fun load(): List<Vector> {
        val json = VectorSuite::class.java.getResourceAsStream("/burnpony_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val root = MiniJson.parseObject(json)
        @Suppress("UNCHECKED_CAST")
        val raw = root["vectors"] as List<Map<String, Any?>>
        return raw.map { v ->
            val payload = MiniJson.parseObject(v["plaintextPayload"] as String)
            @Suppress("UNCHECKED_CAST")
            val envelope = v["envelope"] as Map<String, Any?>
            Vector(
                name = v["name"] as String,
                keyFragment = v["keyFragment"] as String,
                password = v["password"] as String?,
                salt = b64(v["salt"] as String),
                nonce = b64(v["nonce"] as String),
                derivedKey = b64(v["derivedKey"] as String),
                ciphertext = b64(v["ciphertext"] as String),
                plaintextPayload = v["plaintextPayload"] as String,
                text = payload["t"] as String,
                autoHideSeconds = (payload["ah"] as Number).toInt(),
                envelopeJson = BurnPonyCrypto.envelopeJson(
                    NoteEnvelope(
                        v = (envelope["v"] as Number).toInt(),
                        pw = envelope["pw"] as Boolean,
                        salt = envelope["salt"] as String,
                        nonce = envelope["nonce"] as String,
                        ct = envelope["ct"] as String,
                    )
                ),
            )
        }
    }

    fun b64(s: String): ByteArray = Base64Codec.decode(s)
        ?: error("bad base64 in vector file: $s")
}
