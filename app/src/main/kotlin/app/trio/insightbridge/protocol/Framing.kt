/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

/**
 * PROTOCOL.md section 2. One GATT write/notification carries one fragment:
 * flags(1) | messageSeq(uint16 BE) | payload.
 */
object Framing {

    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02
    const val HEADER_SIZE = 3
    const val MAX_MESSAGE_SIZE = 16 * 1024
    const val REASSEMBLY_TIMEOUT_MS = 5_000L

    /** Smallest ATT value size that can carry at least one payload byte. */
    const val MIN_FRAGMENT_SIZE = HEADER_SIZE + 1
}

/** Splits a message into fragments no larger than [maxFragmentSize] bytes (header included). */
object Fragmenter {

    fun fragment(message: ByteArray, messageSeq: Int, maxFragmentSize: Int): List<ByteArray> {
        require(maxFragmentSize >= Framing.MIN_FRAGMENT_SIZE) { "fragment size too small" }
        require(message.size <= Framing.MAX_MESSAGE_SIZE) { "message too large" }
        val chunk = maxFragmentSize - Framing.HEADER_SIZE
        val count = if (message.isEmpty()) 1 else (message.size + chunk - 1) / chunk
        val seq = messageSeq and 0xFFFF
        return List(count) { i ->
            val from = i * chunk
            val to = minOf(message.size, from + chunk)
            var flags = 0
            if (i == 0) flags = flags or Framing.FLAG_FIRST
            if (i == count - 1) flags = flags or Framing.FLAG_LAST
            val out = ByteArray(Framing.HEADER_SIZE + (to - from))
            out[0] = flags.toByte()
            out[1] = (seq shr 8).toByte()
            out[2] = seq.toByte()
            System.arraycopy(message, from, out, Framing.HEADER_SIZE, to - from)
            out
        }
    }
}

/** Result of feeding one fragment to a [Reassembler]. */
sealed interface ReassemblyResult {

    /** Fragment accepted, message not complete yet. */
    data object Incomplete : ReassemblyResult

    /** A complete message. */
    class Message(val bytes: ByteArray) : ReassemblyResult

    /** The fragment (and any partial message) was dropped. */
    class Dropped(val reason: String) : ReassemblyResult

    /** The message exceeded 16 KiB; it was dropped and the peer should be told `tooLarge`. */
    data object TooLarge : ReassemblyResult
}

/**
 * Reassembles fragments of one direction of one link. Holds at most [Framing.MAX_MESSAGE_SIZE]
 * bytes of partial data, so retained state is bounded however hostile the peer is.
 */
class Reassembler(private val clock: () -> Long) {

    private var buffer = ByteArray(0)
    private var size = 0
    private var seq = -1
    private var startedAt = 0L
    private var active = false
    private var discardingSeq = -1

    val hasPartial: Boolean get() = active

    private fun reset() {
        buffer = ByteArray(0)
        size = 0
        active = false
        seq = -1
    }

    /** Drops a partial message older than 5 s. Returns true if one was dropped. */
    fun expire(): Boolean {
        if (active && clock() - startedAt > Framing.REASSEMBLY_TIMEOUT_MS) {
            reset()
            return true
        }
        return false
    }

    fun feed(fragment: ByteArray): ReassemblyResult {
        expire()
        if (fragment.size < Framing.HEADER_SIZE) {
            reset()
            return ReassemblyResult.Dropped("short fragment")
        }
        val flags = fragment[0].toInt() and 0xFF
        if (flags and 0x03.inv() != 0) {
            reset()
            return ReassemblyResult.Dropped("unknown flags")
        }
        val messageSeq = ((fragment[1].toInt() and 0xFF) shl 8) or (fragment[2].toInt() and 0xFF)
        val first = flags and Framing.FLAG_FIRST != 0
        val last = flags and Framing.FLAG_LAST != 0
        val payloadLen = fragment.size - Framing.HEADER_SIZE

        if (first) {
            // A new FIRST always replaces whatever was partially received.
            reset()
            discardingSeq = -1
            seq = messageSeq
            startedAt = clock()
            active = true
        } else {
            if (messageSeq == discardingSeq) return ReassemblyResult.Dropped("discarded message")
            if (!active) return ReassemblyResult.Dropped("fragment without FIRST")
            if (messageSeq != seq) {
                reset()
                return ReassemblyResult.Dropped("sequence mismatch")
            }
        }

        if (size + payloadLen > Framing.MAX_MESSAGE_SIZE) {
            discardingSeq = messageSeq
            reset()
            return ReassemblyResult.TooLarge
        }
        if (buffer.size < size + payloadLen) buffer = buffer.copyOf(maxOf(size + payloadLen, minOf(Framing.MAX_MESSAGE_SIZE, buffer.size * 2)))
        System.arraycopy(fragment, Framing.HEADER_SIZE, buffer, size, payloadLen)
        size += payloadLen

        if (last) {
            val out = buffer.copyOf(size)
            reset()
            return ReassemblyResult.Message(out)
        }
        return ReassemblyResult.Incomplete
    }
}
