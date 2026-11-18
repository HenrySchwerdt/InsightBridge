/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import java.math.BigInteger
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.spongycastle.crypto.ec.CustomNamedCurves

/**
 * Builds `test-vectors.json` from FIXED inputs (all keys are throw-away test values).
 * The same inputs must give the same outputs on the iOS side.
 */
object TestVectorBuilder {

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun seq(start: Int, n: Int) = ByteArray(n) { (start + it).toByte() }

    /** X9.63 uncompressed public key for a raw scalar, computed with Bouncy Castle (independent of the JCA code under test). */
    fun publicFromScalar(scalar: ByteArray): ByteArray {
        val curve = CustomNamedCurves.getByName("secp256r1")
        val q = curve.g.multiply(BigInteger(1, scalar)).normalize()
        return byteArrayOf(0x04) + fixed32(q.affineXCoord.toBigInteger()) + fixed32(q.affineYCoord.toBigInteger())
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        System.arraycopy(raw, maxOf(0, raw.size - 32), out, 32 - minOf(32, raw.size), minOf(32, raw.size))
        return out
    }

    private fun envelopeCase(name: String, directionByte: Int, key: ByteArray, counter: Long, plaintext: ByteArray): JsonObject {
        val env = Envelope.seal(key, directionByte, counter, plaintext)
        return buildJsonObject {
            put("name", name)
            put("direction", if (directionByte == 1) "iphoneToBridge" else "bridgeToIphone")
            put("directionByte", directionByte)
            put("key", hex(key))
            put("counter", counter)
            put("plaintextUtf8", String(plaintext))
            put("plaintextHex", hex(plaintext))
            put("nonce", hex(Envelope.nonce(directionByte, counter)))
            put("aad", hex(env.copyOfRange(0, 9)))
            put("ciphertextAndTag", hex(env.copyOfRange(9, env.size)))
            put("envelope", hex(env))
        }
    }

    fun build(): JsonObject {
        // ---- pairing ----
        val privI = seq(0x10, 32)
        val privB = seq(0x50, 32)
        val pubI = publicFromScalar(privI)
        val pubB = publicFromScalar(privB)
        val nonceI = seq(0xA0, 16)
        val nonceB = seq(0xB0, 16)
        val zI = EcdhP256.sharedSecret(EcdhP256.privateKeyFromScalar(privI), pubB)
        val zB = EcdhP256.sharedSecret(EcdhP256.privateKeyFromScalar(privB), pubI)
        check(zI.contentEquals(zB))
        val ltk = PairingCrypto.deriveLtk(zI, nonceI, nonceB)
        val clientId = "0123456789abcdef"
        val bridgeId = "fedcba9876543210"

        // SAS cases: first one is the pairing above, plus one that needs zero padding
        var leadingZeroLtk: ByteArray? = null
        var i = 0
        while (leadingZeroLtk == null) {
            val c = seq(i, 32)
            if (PairingCrypto.sas(c).startsWith("0")) leadingZeroLtk = c
            i++
        }
        val sasCases = JsonArray(listOf(ltk, seq(0, 32), leadingZeroLtk).map {
            buildJsonObject { put("ltk", hex(it)); put("sas", PairingCrypto.sas(it)) }
        })

        // ---- session ----
        val sNonceI = seq(0xC0, 16)
        val sNonceB = seq(0xD0, 16)
        val sessionKey = PairingCrypto.deriveSessionKey(ltk, sNonceI, sNonceB)

        // ---- envelopes ----
        val envelopes = JsonArray(
            listOf(
                envelopeCase("hello request (iPhone -> bridge, first message)", 1, sessionKey, 1, """{"id":1,"op":"hello","args":{"protocol":2,"app":"Trio 1.0"}}""".toByteArray()),
                envelopeCase("hello response (bridge -> iPhone, first message)", 2, sessionKey, 1, """{"id":1,"ok":true,"result":{"protocol":2,"bridgeVersion":"1.0.0","pumpPaired":true}}""".toByteArray()),
                envelopeCase("getStatus request, counter 2", 1, sessionKey, 2, """{"id":2,"op":"getStatus"}""".toByteArray()),
                envelopeCase("error response, counter 300 (multi-byte counter)", 2, sessionKey, 300, """{"id":9,"ok":false,"error":{"code":"invalidArgs","message":"amount must be a multiple of 0.05 U","uncertain":false}}""".toByteArray())
            )
        )

        // ---- fragmentation ----
        val message = envelopes[0].let { (it as JsonObject)["envelope"] as JsonPrimitive }.content.let { unhex(it) }
        val frags = Fragmenter.fragment(message, 0x0102, 20)
        val frag = buildJsonObject {
            put("maxFragmentSize", 20)
            put("note", "maxFragmentSize is the ATT value size (MTU 23 - 3 = 20) including the 3-byte fragment header")
            put("messageSeq", 0x0102)
            put("message", hex(message))
            put("fragments", JsonArray(frags.map { JsonPrimitive(hex(it)) }))
        }
        val singleFrag = buildJsonObject {
            put("maxFragmentSize", 20)
            put("messageSeq", 7)
            put("message", "010203")
            put("fragments", JsonArray(Fragmenter.fragment(byteArrayOf(1, 2, 3), 7, 20).map { JsonPrimitive(hex(it)) }))
        }

        val hkdf = buildJsonObject {
            put("name", "RFC 5869 test case 1 (sanity check of the HKDF implementation)")
            put("ikm", "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
            put("salt", "000102030405060708090a0b0c")
            put("info", "f0f1f2f3f4f5f6f7f8f9")
            put("length", 42)
            put("okm", hex(Kdf.hkdfSha256(unhex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), unhex("000102030405060708090a0b0c"), unhex("f0f1f2f3f4f5f6f7f8f9"), 42)))
        }

        return buildJsonObject {
            put("protocolVersion", 2)
            put("description", "Fixed test vectors for the InsightBridge protocol (PROTOCOL.md). All keys are throw-away test values. Hex is lowercase, no separators. Base64 is standard with padding.")
            put("conventions", buildJsonObject {
                put("hmac", "HMAC-SHA256")
                put("hkdf", "HKDF-SHA256 (RFC 5869): PRK = HMAC(salt, ikm); OKM = T(1)||T(2).. with T(n) = HMAC(PRK, T(n-1)||info||n)")
                put("ecdh", "P-256 (secp256r1); Z = 32-byte X coordinate of the shared point; public keys are X9.63 uncompressed (04||X||Y, 65 bytes)")
                put("strings", "all strings used as HKDF info / HMAC messages are ASCII bytes without terminator; nonces are concatenated as raw bytes")
                put("gcm", "AES-256-GCM, 96-bit nonce = direction(1) || 000000 || counter(uint64 BE), AAD = envelope bytes 0..8 (type + counter), tag 16 bytes appended to the ciphertext")
            })
            put("hkdfSanity", hkdf)
            put("pairing", buildJsonObject {
                put("initiatorPrivateScalar", hex(privI))
                put("initiatorPublicKey", hex(pubI))
                put("bridgePrivateScalar", hex(privB))
                put("bridgePublicKey", hex(pubB))
                put("nonceI", hex(nonceI))
                put("nonceB", hex(nonceB))
                put("sharedSecretZ", hex(zI))
                put("ltkSalt", hex(nonceI + nonceB))
                put("ltkInfo", "InsightBridge LTK v1")
                put("ltk", hex(ltk))
                put("sas", PairingCrypto.sas(ltk))
                put("sasFormula", "uint32BE(HMAC-SHA256(LTK, \"SAS\")[0..3]) mod 1000000, zero padded to 6 digits")
                put("pairConfirmMac", hex(PairingCrypto.confirmMacInitiator(ltk)))
                put("pairDoneMac", hex(PairingCrypto.confirmMacBridge(ltk)))
                put("clientId", clientId)
                put("bridgeId", bridgeId)
                put("commitment", buildJsonObject {
                    put("input", "ASCII \"InsightBridge commit v2\" || pubI (65 raw bytes) || nonceI (16 raw bytes)")
                    put("commit", hex(PairingCrypto.commitment(pubI, nonceI)))
                    put("wrongNonceI", hex(seq(0xA1, 16)))
                    put("commitWithWrongNonceI", hex(PairingCrypto.commitment(pubI, seq(0xA1, 16))))
                    put("note", "bridge recomputes SHA-256 over the revealed pub/nonce and compares in constant time; mismatch -> pairReject commitMismatch")
                })
                put("messages", buildJsonObject {
                    put("pairStart", String(HandshakeCodec.pairStart(PairingCrypto.commitment(pubI, nonceI))))
                    put("pairReveal", String(HandshakeCodec.pairReveal(pubI, nonceI)))
                    put("pairResp", String(HandshakeCodec.pairResp(pubB, nonceB, bridgeId)))
                    put("pairConfirm", String(HandshakeCodec.pairConfirm(PairingCrypto.confirmMacInitiator(ltk))))
                    put("pairDone", String(HandshakeCodec.pairDone(PairingCrypto.confirmMacBridge(ltk))))
                    put("note", "JSON field order is irrelevant; shown for the exact field names and base64 encoding. As envelopes these are sent as 0x01 || utf8-json.")
                })
            })
            put("sasCases", sasCases)
            put("session", buildJsonObject {
                put("ltk", hex(ltk))
                put("clientId", clientId)
                put("bridgeId", bridgeId)
                put("nonceI", hex(sNonceI))
                put("nonceB", hex(sNonceB))
                put("macInput", "ASCII \"session\" || nonceI || nonceB")
                put("mac", hex(PairingCrypto.sessionMac(ltk, sNonceI, sNonceB)))
                put("sessionKeySalt", hex(sNonceI + sNonceB))
                put("sessionKeyInfo", "InsightBridge SK v1")
                put("sessionKey", hex(sessionKey))
                put("messages", buildJsonObject {
                    put("sessionStart", String(HandshakeCodec.sessionStart(clientId, sNonceI)))
                    put("sessionResp", String(HandshakeCodec.sessionResp(bridgeId, sNonceB, PairingCrypto.sessionMac(ltk, sNonceI, sNonceB))))
                })
            })
            put("envelopes", envelopes)
            put("fragmentation", frag)
            put("fragmentationSingle", singleFrag)
        }
    }

    fun prettyPrint(obj: JsonElement): String = kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = "  " }
        .encodeToString(JsonElement.serializer(), obj) + "\n"

    @Suppress("unused") private val nul = JsonNull
    @Suppress("unused") private fun b(x: ByteArray) = b64(x)
}
