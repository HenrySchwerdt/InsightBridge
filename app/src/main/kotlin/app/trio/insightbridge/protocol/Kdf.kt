/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 / HKDF-SHA256 (RFC 5869) with only JCA primitives. */
object Kdf {

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An empty key is legal for HMAC but SecretKeySpec rejects it; HMAC pads with zeros anyway.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/** ECDH on P-256 with X9.63 uncompressed public keys (0x04 || X || Y). */
object EcdhP256 {

    private val params: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }
    private val p: BigInteger get() = (params.curve.field as java.security.spec.ECFieldFp).p

    class KeyPairBytes(val privateKey: PrivateKey, val publicKey: ByteArray)

    fun generate(random: SecureRandom = SecureRandom()): KeyPairBytes {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), random)
        val kp = kpg.generateKeyPair()
        val w = (kp.public as java.security.interfaces.ECPublicKey).w
        return KeyPairBytes(kp.private, encodePoint(w))
    }

    /** Builds a private key from a raw 32-byte scalar (used by deterministic test vectors). */
    fun privateKeyFromScalar(scalar: ByteArray): PrivateKey {
        val d = BigInteger(1, scalar)
        require(d.signum() > 0 && d < params.order) { "scalar out of range" }
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, params))
    }

    fun encodePoint(w: ECPoint): ByteArray {
        val out = ByteArray(65)
        out[0] = 0x04
        fixed32(w.affineX).copyInto(out, 1)
        fixed32(w.affineY).copyInto(out, 33)
        return out
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        val len = minOf(raw.size, 32)
        System.arraycopy(raw, raw.size - len, out, 32 - len, len)
        return out
    }

    /** Decodes and validates (on-curve, not infinity, coordinates < p). Throws IllegalArgumentException. */
    fun decodePublic(bytes: ByteArray): java.security.PublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "public key must be 65-byte X9.63 uncompressed" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        require(x < p && y < p) { "coordinate out of range" }
        val lhs = y.multiply(y).mod(p)
        val rhs = x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        require(lhs == rhs) { "point not on curve" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    /** Raw shared secret Z (32 bytes, the X coordinate). */
    fun sharedSecret(privateKey: PrivateKey, peerPublic: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(decodePublic(peerPublic), true)
        return ka.generateSecret()
    }
}

/** PROTOCOL.md section 4: pairing key derivation, SAS and confirmation MACs. */
object PairingCrypto {

    private val LTK_INFO = "InsightBridge LTK v1".toByteArray(Charsets.US_ASCII)
    private val SK_INFO = "InsightBridge SK v1".toByteArray(Charsets.US_ASCII)

    private val COMMIT_PREFIX = "InsightBridge commit v2".toByteArray(Charsets.US_ASCII)

    /** SHA-256("InsightBridge commit v2" || pubI(65 raw bytes) || nonceI(16 raw bytes)). */
    fun commitment(pubI: ByteArray, nonceI: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(COMMIT_PREFIX); update(pubI); update(nonceI); digest()
        }

    fun deriveLtk(z: ByteArray, nonceI: ByteArray, nonceB: ByteArray): ByteArray =
        Kdf.hkdfSha256(z, nonceI + nonceB, LTK_INFO, 32)

    /** SAS = uint32BE(HMAC-SHA256(LTK, "SAS")[0..3]) mod 1_000_000, zero padded to 6 digits. */
    fun sas(ltk: ByteArray): String {
        val h = Kdf.hmacSha256(ltk, "SAS".toByteArray(Charsets.US_ASCII))
        val v = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (v % 1_000_000L).toString().padStart(6, '0')
    }

    fun confirmMacInitiator(ltk: ByteArray): ByteArray = Kdf.hmacSha256(ltk, "confirm-i".toByteArray(Charsets.US_ASCII))
    fun confirmMacBridge(ltk: ByteArray): ByteArray = Kdf.hmacSha256(ltk, "confirm-b".toByteArray(Charsets.US_ASCII))

    /** MAC the bridge sends in sessionResp: HMAC-SHA256(LTK, "session" || nonceI || nonceB). */
    fun sessionMac(ltk: ByteArray, nonceI: ByteArray, nonceB: ByteArray): ByteArray =
        Kdf.hmacSha256(ltk, "session".toByteArray(Charsets.US_ASCII), nonceI, nonceB)

    fun deriveSessionKey(ltk: ByteArray, nonceI: ByteArray, nonceB: ByteArray): ByteArray =
        Kdf.hkdfSha256(ltk, nonceI + nonceB, SK_INFO, 32)
}
