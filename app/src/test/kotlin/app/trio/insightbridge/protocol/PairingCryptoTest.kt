/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PairingCryptoTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val okm = Kdf.hkdfSha256(
            ikm = ByteArray(22) { 0x0b }, salt = hex("000102030405060708090a0b0c"), info = hex("f0f1f2f3f4f5f6f7f8f9"), length = 42
        )
        assertThat(okm).isEqualTo(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"))
    }

    @Test
    fun hmacMatchesRfc4231TestCase2() {
        val mac = Kdf.hmacSha256("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray())
        assertThat(mac).isEqualTo(hex("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"))
    }

    @Test
    fun ecdhAgreesOnBothSides() {
        val a = EcdhP256.generate()
        val b = EcdhP256.generate()
        assertThat(a.publicKey).hasLength(65)
        assertThat(a.publicKey[0]).isEqualTo(0x04.toByte())
        assertThat(EcdhP256.sharedSecret(a.privateKey, b.publicKey)).isEqualTo(EcdhP256.sharedSecret(b.privateKey, a.publicKey))
    }

    @Test
    fun invalidPublicKeysAreRejected() {
        val a = EcdhP256.generate()
        assertThrows<IllegalArgumentException> { EcdhP256.decodePublic(ByteArray(64)) }
        assertThrows<IllegalArgumentException> { EcdhP256.decodePublic(ByteArray(65)) } // wrong prefix
        val offCurve = a.publicKey.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertThrows<IllegalArgumentException> { EcdhP256.decodePublic(offCurve) }
        val infinity = ByteArray(65).also { it[0] = 4 }
        assertThrows<IllegalArgumentException> { EcdhP256.decodePublic(infinity) }
        assertThrows<IllegalArgumentException> { EcdhP256.decodePublic(byteArrayOf(0x02) + ByteArray(32)) } // compressed
    }

    @Test
    fun sasIsSixDigitsZeroPaddedAndDeterministic() {
        val seen = HashSet<String>()
        for (i in 0 until 400) {
            val sas = PairingCrypto.sas(kotlin.random.Random(i).nextBytes(32))
            assertThat(sas).matches("[0-9]{6}")
            seen.add(sas)
        }
        assertThat(seen.size).isGreaterThan(300)
        assertThat(seen.any { it.startsWith("0") }).isTrue()
        val ltk = ByteArray(32) { 5 }
        assertThat(PairingCrypto.sas(ltk)).isEqualTo(PairingCrypto.sas(ltk))
    }

    @Test
    fun sasFollowsTheSpecFormula() {
        val ltk = ByteArray(32) { (it * 3).toByte() }
        val h = Kdf.hmacSha256(ltk, "SAS".toByteArray())
        val v = java.nio.ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xFFFFFFFFL
        assertThat(PairingCrypto.sas(ltk)).isEqualTo((v % 1_000_000).toString().padStart(6, '0'))
    }

    @Test
    fun macsAreDomainSeparated() {
        val ltk = ByteArray(32) { 1 }
        assertThat(PairingCrypto.confirmMacInitiator(ltk)).isNotEqualTo(PairingCrypto.confirmMacBridge(ltk))
        val n1 = ByteArray(16) { 2 }; val n2 = ByteArray(16) { 3 }
        assertThat(PairingCrypto.sessionMac(ltk, n1, n2)).isNotEqualTo(PairingCrypto.sessionMac(ltk, n2, n1))
        assertThat(PairingCrypto.deriveSessionKey(ltk, n1, n2)).isNotEqualTo(PairingCrypto.deriveSessionKey(ltk, n2, n1))
    }

    @Test
    fun ltkDependsOnBothNoncesInOrder() {
        val z = ByteArray(32) { 9 }
        val a = ByteArray(16) { 1 }; val b = ByteArray(16) { 2 }
        assertThat(PairingCrypto.deriveLtk(z, a, b)).isNotEqualTo(PairingCrypto.deriveLtk(z, b, a))
        assertThat(PairingCrypto.deriveLtk(z, a, b)).hasLength(32)
    }
}
