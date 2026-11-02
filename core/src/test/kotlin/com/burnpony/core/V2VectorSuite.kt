//
// V2VectorSuite.kt
// Loads the envelope v2 cross-implementation vectors
// (shared/burnpony_v2_vectors.json, copied into test resources). Format FROZEN
// (Aug 27, 2026). Ground truth: Python; cross-checked against WebCrypto and Swift.
//

package com.burnpony.core

class V2ShareVec(
    val x: Int,
    val password: String?,
    val salt: ByteArray?,
    val wrapNonce: ByteArray?,
    val fragment: String,
)

class V2ThresholdVector(
    val master: ByteArray,
    val t: Int,
    val n: Int,
    val coeff: Array<IntArray>,
    val shares: List<V2ShareVec>,
    val envelope: NoteEnvelope,
    val text: String,
    val autoHideSeconds: Int,
)

class V2SlotVec(val index: Int, val password: String, val text: String, val autoHideSeconds: Int)

class V2DecoyVector(
    val fragmentKey: ByteArray,
    val salt: ByteArray,
    val slotNonces: List<ByteArray>,
    val slots: List<V2SlotVec>,
    val envelope: SlottedEnvelope,
    val slotLen: Int,
)

object V2VectorSuite {

    val decoySlots: Int by lazy { (root()["decoySlots"] as Number).toInt() }
    val threshold: V2ThresholdVector by lazy { loadThreshold() }
    val decoy: V2DecoyVector by lazy { loadDecoy() }

    private fun root(): Map<String, Any?> {
        val json = V2VectorSuite::class.java.getResourceAsStream("/burnpony_v2_vectors.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return MiniJson.parseObject(json)
    }

    @Suppress("UNCHECKED_CAST")
    private fun vectorNamed(prefix: String): Map<String, Any?> =
        (root()["vectors"] as List<Map<String, Any?>>).first { (it["name"] as String).startsWith(prefix) }

    private fun b64(s: String): ByteArray = Base64Codec.decode(s) ?: error("bad base64: $s")

    @Suppress("UNCHECKED_CAST")
    private fun loadThreshold(): V2ThresholdVector {
        val v = vectorNamed("v2-threshold")
        val coeffList = v["coeffBytes"] as List<Any?>
        val coeff = Array(coeffList.size) { intArrayOf((coeffList[it] as Number).toInt()) }
        val shares = (v["shares"] as List<Map<String, Any?>>).map {
            V2ShareVec(
                x = (it["x"] as Number).toInt(),
                password = it["password"] as String?,
                salt = (it["salt"] as String?)?.let { s -> b64(s) },
                wrapNonce = (it["wrapNonce"] as String?)?.let { s -> b64(s) },
                fragment = it["fragment"] as String,
            )
        }
        val e = v["envelope"] as Map<String, Any?>
        val payload = MiniJson.parseObject(v["plaintextPayload"] as String)
        return V2ThresholdVector(
            master = b64(v["masterSecret"] as String),
            t = (v["t"] as Number).toInt(),
            n = (v["n"] as Number).toInt(),
            coeff = coeff,
            shares = shares,
            envelope = NoteEnvelope((e["v"] as Number).toInt(), e["pw"] as Boolean,
                e["salt"] as String, e["nonce"] as String, e["ct"] as String),
            text = payload["t"] as String,
            autoHideSeconds = (payload["ah"] as Number).toInt(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadDecoy(): V2DecoyVector {
        val v = vectorNamed("v2-decoy")
        val slots = (v["slots"] as List<Map<String, Any?>>).map {
            val p = MiniJson.parseObject(it["plaintextPayload"] as String)
            V2SlotVec((it["index"] as Number).toInt(), it["password"] as String,
                p["t"] as String, (p["ah"] as Number).toInt())
        }
        val e = v["envelope"] as Map<String, Any?>
        return V2DecoyVector(
            fragmentKey = b64(v["fragmentKey"] as String),
            salt = b64(v["salt"] as String),
            slotNonces = (v["slotNonces"] as List<Any?>).map { b64(it as String) },
            slots = slots,
            envelope = SlottedEnvelope((e["v"] as Number).toInt(), e["pw"] as Boolean,
                e["salt"] as String, e["ct"] as String),
            slotLen = (v["slotLen"] as Number).toInt(),
        )
    }
}
