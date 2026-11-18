/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EnvelopeTest {

    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun nonceLayoutIsDirectionThenThreeZeroBytesThenCounterBigEndian() {
        val n = Envelope.nonce(Envelope.DIR_IPHONE_TO_BRIDGE, 0x0102030405060708L)
        assertThat(n).isEqualTo(byteArrayOf(1, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8))
        val m = Envelope.nonce(Envelope.DIR_BRIDGE_TO_IPHONE, 1)
        assertThat(m).isEqualTo(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
    }

    @Test
    fun envelopeLayoutTypeCounterCiphertextTag() {
        val plain = "hello".toByteArray()
        val env = Envelope.seal(key, Envelope.DIR_IPHONE_TO_BRIDGE, 7, plain)
        assertThat(env[0]).isEqualTo(0x02.toByte())
        assertThat(ByteBuffer.wrap(env, 1, 8).long).isEqualTo(7L)
        assertThat(env.size).isEqualTo(9 + plain.size + 16)
    }

    @Test
    fun roundTripBothDirections() {
        val plain = """{"id":1,"op":"getStatus"}""".toByteArray()
        for (dir in listOf(Envelope.DIR_IPHONE_TO_BRIDGE, Envelope.DIR_BRIDGE_TO_IPHONE)) {
            val env = Envelope.seal(key, dir, 42, plain)
            assertThat(Envelope.open(key, dir, env)).isEqualTo(plain)
        }
    }

    @Test
    fun wrongDirectionFailsAuthentication() {
        val env = Envelope.seal(key, Envelope.DIR_IPHONE_TO_BRIDGE, 1, byteArrayOf(1))
        val e = assertThrows<EnvelopeException> { Envelope.open(key, Envelope.DIR_BRIDGE_TO_IPHONE, env) }
        assertThat(e.kind).isEqualTo(EnvelopeException.Kind.AUTH_FAILED)
    }

    @Test
    fun anyTamperedByteIsRejected() {
        val env = Envelope.seal(key, Envelope.DIR_IPHONE_TO_BRIDGE, 5, "payload".toByteArray())
        for (i in 1 until env.size) { // byte 0 is the type, checked separately
            val t = env.copyOf()
            t[i] = (t[i].toInt() xor 0x01).toByte()
            assertThrows<EnvelopeException>("byte $i") { Envelope.open(key, Envelope.DIR_IPHONE_TO_BRIDGE, t) }
        }
    }

    @Test
    fun tagTamperingIsAuthFailure() {
        val env = Envelope.seal(key, Envelope.DIR_IPHONE_TO_BRIDGE, 5, "payload".toByteArray())
        env[env.size - 1] = (env[env.size - 1].toInt() xor 0xFF).toByte()
        assertThat(assertThrows<EnvelopeException> { Envelope.open(key, Envelope.DIR_IPHONE_TO_BRIDGE, env) }.kind)
            .isEqualTo(EnvelopeException.Kind.AUTH_FAILED)
    }

    @Test
    fun wrongKeyIsRejected() {
        val env = Envelope.seal(key, Envelope.DIR_IPHONE_TO_BRIDGE, 1, byteArrayOf(1))
        assertThrows<EnvelopeException> { Envelope.open(ByteArray(32), Envelope.DIR_IPHONE_TO_BRIDGE, env) }
    }

    @Test
    fun truncatedAndWrongTypeAreMalformed() {
        assertThat(assertThrows<EnvelopeException> { Envelope.open(key, 1, ByteArray(10)) }.kind).isEqualTo(EnvelopeException.Kind.MALFORMED)
        val env = Envelope.seal(key, 1, 1, byteArrayOf(1))
        env[0] = 0x01
        assertThat(assertThrows<EnvelopeException> { Envelope.open(key, 1, env) }.kind).isEqualTo(EnvelopeException.Kind.MALFORMED)
    }

    @Test
    fun counterZeroIsMalformed() {
        val env = Envelope.seal(key, 1, 1, byteArrayOf(1))
        for (i in 1..8) env[i] = 0
        assertThat(assertThrows<EnvelopeException> { Envelope.open(key, 1, env) }.kind).isEqualTo(EnvelopeException.Kind.MALFORMED)
    }

    @Test
    fun channelsStartAtOneAndIncrease() {
        val tx = SealingChannel(key, Envelope.DIR_IPHONE_TO_BRIDGE)
        val e1 = tx.seal(byteArrayOf(1)); val e2 = tx.seal(byteArrayOf(2))
        assertThat(Envelope.counterOf(e1)).isEqualTo(1)
        assertThat(Envelope.counterOf(e2)).isEqualTo(2)
    }

    @Test
    fun replayAndNonIncreasingCountersAreRejected() {
        val tx = SealingChannel(key, Envelope.DIR_IPHONE_TO_BRIDGE)
        val rx = OpeningChannel(key, Envelope.DIR_IPHONE_TO_BRIDGE)
        val e1 = tx.seal(byteArrayOf(1)); val e2 = tx.seal(byteArrayOf(2)); val e3 = tx.seal(byteArrayOf(3))
        assertThat(rx.open(e1)).isEqualTo(byteArrayOf(1))
        assertThat(rx.open(e3)).isEqualTo(byteArrayOf(3)) // gaps are allowed, going backwards is not
        assertThat(assertThrows<EnvelopeException> { rx.open(e1) }.kind).isEqualTo(EnvelopeException.Kind.REPLAY)
        assertThat(assertThrows<EnvelopeException> { rx.open(e2) }.kind).isEqualTo(EnvelopeException.Kind.REPLAY)
        assertThat(assertThrows<EnvelopeException> { rx.open(e3) }.kind).isEqualTo(EnvelopeException.Kind.REPLAY)
        assertThat(rx.lastAccepted()).isEqualTo(3)
    }

    @Test
    fun forgedEnvelopeDoesNotAdvanceTheCounter() {
        val tx = SealingChannel(key, Envelope.DIR_IPHONE_TO_BRIDGE)
        val rx = OpeningChannel(key, Envelope.DIR_IPHONE_TO_BRIDGE)
        val forged = Envelope.seal(ByteArray(32), Envelope.DIR_IPHONE_TO_BRIDGE, 1000, byteArrayOf(9))
        assertThrows<EnvelopeException> { rx.open(forged) }
        assertThat(rx.lastAccepted()).isEqualTo(0)
        assertThat(rx.open(tx.seal(byteArrayOf(1)))).isEqualTo(byteArrayOf(1))
    }

    @Test
    fun plaintextEnvelopeIsTypeByteThenJson() {
        assertThat(Envelope.plaintext("{}".toByteArray())).isEqualTo(byteArrayOf(1, '{'.code.toByte(), '}'.code.toByte()))
    }
}
