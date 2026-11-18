/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import java.util.Base64
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Plaintext handshake messages (envelope type 0x01), PROTOCOL.md section 4. */
sealed interface Handshake {

    data class PairStart(val version: Int, val commit: ByteArray, val clientId: String?) : Handshake
    data class PairReveal(val pub: ByteArray, val nonce: ByteArray) : Handshake
    data class PairConfirm(val mac: ByteArray) : Handshake
    data class SessionStart(val version: Int, val clientId: String, val nonce: ByteArray) : Handshake
    /** Anything else (including bridge-originated types arriving from a peer). */
    data class Unknown(val type: String?) : Handshake
}

object HandshakeCodec {

    const val NONCE_BYTES = 16
    private val HEX16 = Regex("^[0-9a-fA-F]{16}$")

    class Invalid(message: String) : Exception(message)

    fun isValidId(s: String) = HEX16.matches(s)

    private fun b64(o: JsonObject, name: String, expectedLen: Int? = null): ByteArray {
        val p = o[name] as? JsonPrimitive
        if (p == null || p is JsonNull || !p.isString) throw Invalid("missing $name")
        val bytes = try {
            Base64.getDecoder().decode(p.content)
        } catch (e: IllegalArgumentException) {
            throw Invalid("bad base64 in $name")
        }
        if (expectedLen != null && bytes.size != expectedLen) throw Invalid("bad length of $name")
        return bytes
    }

    private fun version(o: JsonObject): Int {
        val p = o["v"] as? JsonPrimitive ?: throw Invalid("missing v")
        return p.content.toIntOrNull() ?: throw Invalid("bad v")
    }

    /** Parses a plaintext handshake body. Throws [Invalid] for malformed content. */
    fun parse(json: ByteArray): Handshake {
        val o = try {
            RequestParser.parseObject(json)
        } catch (e: UnparseableFrame) {
            throw Invalid("not a JSON object")
        }
        val type = (o["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (type) {
            "pairStart" -> {
                val clientId = (o["clientId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (clientId != null && !isValidId(clientId)) throw Invalid("bad clientId")
                Handshake.PairStart(version(o), b64(o, "commit", 32), clientId?.lowercase())
            }
            "pairReveal" -> Handshake.PairReveal(b64(o, "pub", 65), b64(o, "nonce", NONCE_BYTES))
            "pairConfirm" -> Handshake.PairConfirm(b64(o, "mac", 32))
            "sessionStart" -> {
                val clientId = (o["clientId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (clientId == null || !isValidId(clientId)) throw Invalid("bad clientId")
                Handshake.SessionStart(version(o), clientId.lowercase(), b64(o, "nonce", NONCE_BYTES))
            }
            else -> Handshake.Unknown(type)
        }
    }

    private fun enc(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    fun pairResp(pub: ByteArray, nonce: ByteArray, bridgeIdHex: String) = Wire.encode(buildJsonObject {
        put("t", "pairResp"); put("v", 2); put("pub", enc(pub)); put("nonce", enc(nonce)); put("bridgeId", bridgeIdHex)
    })

    fun pairDone(mac: ByteArray) = Wire.encode(buildJsonObject { put("t", "pairDone"); put("mac", enc(mac)) })
    fun pairReject(reason: String) = Wire.encode(buildJsonObject { put("t", "pairReject"); put("reason", reason) })

    fun sessionResp(bridgeIdHex: String, nonce: ByteArray, mac: ByteArray) = Wire.encode(buildJsonObject {
        put("t", "sessionResp"); put("v", 2); put("bridgeId", bridgeIdHex); put("nonce", enc(nonce)); put("mac", enc(mac))
    })

    fun sessionReject(reason: String) = Wire.encode(buildJsonObject { put("t", "sessionReject"); put("reason", reason) })

    /** Plaintext error used before a session exists (busy, tooLarge, ...). */
    fun error(code: ErrorCode, message: String) = Wire.encode(buildJsonObject {
        put("t", "error"); put("code", code.wire); put("message", message)
    })

    // client side (used by tests and documented for the iOS implementation)
    fun pairStart(commit: ByteArray, clientId: String? = null, version: Int = 2) = Wire.encode(buildJsonObject {
        put("t", "pairStart"); put("v", version); put("commit", enc(commit))
        if (clientId != null) put("clientId", clientId)
    })

    fun pairReveal(pub: ByteArray, nonce: ByteArray) = Wire.encode(buildJsonObject {
        put("t", "pairReveal"); put("pub", enc(pub)); put("nonce", enc(nonce))
    })

    fun pairConfirm(mac: ByteArray) = Wire.encode(buildJsonObject { put("t", "pairConfirm"); put("mac", enc(mac)) })

    fun sessionStart(clientId: String, nonce: ByteArray, version: Int = 2) = Wire.encode(buildJsonObject {
        put("t", "sessionStart"); put("v", version); put("clientId", clientId); put("nonce", enc(nonce))
    })
}
