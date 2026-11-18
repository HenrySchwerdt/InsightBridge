/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FramingTest {

    private var now = 0L
    private fun reassembler() = Reassembler { now }

    private fun feedAll(r: Reassembler, frags: List<ByteArray>): ReassemblyResult {
        var last: ReassemblyResult = ReassemblyResult.Incomplete
        frags.forEach { last = r.feed(it) }
        return last
    }

    @Test
    fun singleFragmentMessageUsesFlags0x03() {
        val frags = Fragmenter.fragment(byteArrayOf(1, 2, 3), 0x0102, 20)
        assertThat(frags).hasSize(1)
        assertThat(frags[0]).isEqualTo(byteArrayOf(0x03, 0x01, 0x02, 1, 2, 3))
    }

    @Test
    fun fragmentsAtMtu20RoundTripAndNeverExceed20Bytes() {
        val msg = ByteArray(1000) { (it * 7).toByte() }
        val frags = Fragmenter.fragment(msg, 77, 20)
        assertThat(frags.all { it.size <= 20 }).isTrue()
        assertThat(frags.first()[0].toInt()).isEqualTo(0x01)
        assertThat(frags.last()[0].toInt()).isEqualTo(0x02)
        assertThat(frags.drop(1).dropLast(1).all { it[0].toInt() == 0 }).isTrue()
        val result = feedAll(reassembler(), frags)
        assertThat((result as ReassemblyResult.Message).bytes).isEqualTo(msg)
    }

    @Test
    fun emptyMessageIsOneFragment() {
        val frags = Fragmenter.fragment(ByteArray(0), 1, 20)
        assertThat(frags).hasSize(1)
        assertThat((reassembler().feed(frags[0]) as ReassemblyResult.Message).bytes).isEmpty()
    }

    @Test
    fun messageSeqWrapsAt16Bits() {
        val frags = Fragmenter.fragment(byteArrayOf(9), 0x1FFFF, 20)
        assertThat(frags[0][1]).isEqualTo(0xFF.toByte())
        assertThat(frags[0][2]).isEqualTo(0xFF.toByte())
    }

    @Test
    fun newFirstFragmentResetsPartialMessage() {
        val r = reassembler()
        val a = Fragmenter.fragment(ByteArray(50) { 1 }, 1, 20)
        val b = Fragmenter.fragment(ByteArray(10) { 2 }, 2, 20)
        r.feed(a[0]); r.feed(a[1])
        val result = feedAll(r, b)
        assertThat((result as ReassemblyResult.Message).bytes).isEqualTo(ByteArray(10) { 2 })
        // leftover of message 1 is ignored
        assertThat(r.feed(a[2])).isInstanceOf(ReassemblyResult.Dropped::class.java)
    }

    @Test
    fun fragmentWithDifferentSeqDiscardsPartial() {
        val r = reassembler()
        val a = Fragmenter.fragment(ByteArray(50) { 1 }, 1, 20)
        val b = Fragmenter.fragment(ByteArray(50) { 2 }, 2, 20)
        r.feed(a[0])
        assertThat(r.feed(b[1])).isInstanceOf(ReassemblyResult.Dropped::class.java)
        assertThat(r.hasPartial).isFalse()
        // rest of a cannot complete any more
        assertThat(r.feed(a[1])).isInstanceOf(ReassemblyResult.Dropped::class.java)
    }

    @Test
    fun outOfOrderFragmentsNeverProduceAMessage() {
        val r = reassembler()
        val frags = Fragmenter.fragment(ByteArray(60) { it.toByte() }, 5, 20)
        // LAST before FIRST
        assertThat(r.feed(frags.last())).isInstanceOf(ReassemblyResult.Dropped::class.java)
        assertThat(r.feed(frags[1])).isInstanceOf(ReassemblyResult.Dropped::class.java)
        assertThat(r.hasPartial).isFalse()
    }

    @Test
    fun partialMessageExpiresAfterFiveSeconds() {
        val r = reassembler()
        val frags = Fragmenter.fragment(ByteArray(60), 5, 20)
        r.feed(frags[0])
        now = 5_001
        assertThat(r.expire()).isTrue()
        assertThat(r.feed(frags[1])).isInstanceOf(ReassemblyResult.Dropped::class.java)
    }

    @Test
    fun lastFragmentJustWithinFiveSecondsStillCompletes() {
        val r = reassembler()
        val msg = ByteArray(60) { 3 }
        val frags = Fragmenter.fragment(msg, 5, 20)
        r.feed(frags[0])
        now = 5_000
        frags.drop(1).dropLast(1).forEach { r.feed(it) }
        assertThat(r.feed(frags.last())).isInstanceOf(ReassemblyResult.Message::class.java)
    }

    @Test
    fun exactly16KiBIsAcceptedAndOneByteMoreIsTooLarge() {
        val ok = Fragmenter.fragment(ByteArray(Framing.MAX_MESSAGE_SIZE), 1, 512)
        assertThat(feedAll(reassembler(), ok)).isInstanceOf(ReassemblyResult.Message::class.java)

        // craft a too large message by hand (the Fragmenter itself refuses to create one)
        assertThrows<IllegalArgumentException> { Fragmenter.fragment(ByteArray(Framing.MAX_MESSAGE_SIZE + 1), 1, 512) }
        val r = reassembler()
        val first = byteArrayOf(0x01, 0, 9) + ByteArray(10_000)
        val second = byteArrayOf(0x00, 0, 9) + ByteArray(6_385)
        val third = byteArrayOf(0x02, 0, 9) + ByteArray(5)
        assertThat(r.feed(first)).isEqualTo(ReassemblyResult.Incomplete)
        assertThat(r.feed(second)).isEqualTo(ReassemblyResult.TooLarge)
        // remaining fragments of the dropped message are ignored, no second error
        assertThat(r.feed(third)).isInstanceOf(ReassemblyResult.Dropped::class.java)
        assertThat(r.hasPartial).isFalse()
    }

    @Test
    fun malformedFragmentsAreDropped() {
        val r = reassembler()
        assertThat(r.feed(byteArrayOf(0x03, 0x00))).isInstanceOf(ReassemblyResult.Dropped::class.java)
        assertThat(r.feed(byteArrayOf(0x07, 0x00, 0x01, 5))).isInstanceOf(ReassemblyResult.Dropped::class.java)
    }

    @Test
    fun fragmenterRejectsTooSmallFragmentSize() {
        assertThrows<IllegalArgumentException> { Fragmenter.fragment(byteArrayOf(1), 1, 3) }
    }
}
