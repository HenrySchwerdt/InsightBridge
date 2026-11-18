/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown for envelopes that must be rejected; callers drop the connection. */
class EnvelopeException(val kind: Kind, message: String) : Exception(message) {

    enum class Kind { MALFORMED, REPLAY, AUTH_FAILED }
}

/** PROTOCOL.md section 3. */
object Envelope {

    const val TYPE_PLAINTEXT = 0x01
    const val TYPE_ENCRYPTED = 0x02
    const val DIR_IPHONE_TO_BRIDGE = 0x01
    const val DIR_BRIDGE_TO_IPHONE = 0x02
    const val TAG_BYTES = 16
    const val HEADER_BYTES = 9
    const val MIN_ENCRYPTED = HEADER_BYTES + TAG_BYTES

    fun plaintext(json: ByteArray): ByteArray = byteArrayOf(TYPE_PLAINTEXT.toByte()) + json

    /** direction || 0x000000 || counter(uint64 BE) */
    fun nonce(direction: Int, counter: Long): ByteArray {
        val b = ByteBuffer.allocate(12)
        b.put(direction.toByte()).put(0).put(0).put(0).putLong(counter)
        return b.array()
    }

    fun seal(key: ByteArray, direction: Int, counter: Long, plaintext: ByteArray): ByteArray {
        require(counter > 0) { "counter starts at 1" }
        val header = ByteBuffer.allocate(HEADER_BYTES).put(TYPE_ENCRYPTED.toByte()).putLong(counter).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(direction, counter)))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plaintext)
    }

    /** Counter of an encrypted envelope, or throws MALFORMED. */
    fun counterOf(envelope: ByteArray): Long {
        if (envelope.size < MIN_ENCRYPTED || envelope[0] != TYPE_ENCRYPTED.toByte()) {
            throw EnvelopeException(EnvelopeException.Kind.MALFORMED, "not an encrypted envelope")
        }
        val c = ByteBuffer.wrap(envelope, 1, 8).long
        if (c <= 0) throw EnvelopeException(EnvelopeException.Kind.MALFORMED, "bad counter")
        return c
    }

    fun open(key: ByteArray, direction: Int, envelope: ByteArray): ByteArray {
        val counter = counterOf(envelope)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(direction, counter)))
            cipher.updateAAD(envelope, 0, HEADER_BYTES)
            return cipher.doFinal(envelope, HEADER_BYTES, envelope.size - HEADER_BYTES)
        } catch (e: java.security.GeneralSecurityException) {
            throw EnvelopeException(EnvelopeException.Kind.AUTH_FAILED, "authentication failed")
        }
    }
}

/** Sending half of a session: strictly increasing counters starting at 1. Thread safe. */
class SealingChannel(key: ByteArray, private val direction: Int) {

    // own copy: callers wipe their key material after creating the channel
    private val key = key.copyOf()

    private var counter = 0L

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        if (counter == Long.MAX_VALUE) throw IllegalStateException("counter exhausted")
        counter++
        return Envelope.seal(key, direction, counter, plaintext)
    }

    @Synchronized fun lastCounter(): Long = counter
}

/** Receiving half: rejects counters <= the last accepted one, and any tag failure. */
class OpeningChannel(key: ByteArray, private val direction: Int) {

    private val key = key.copyOf()

    private var last = 0L

    @Synchronized
    fun open(envelope: ByteArray): ByteArray {
        val counter = Envelope.counterOf(envelope)
        if (counter <= last) throw EnvelopeException(EnvelopeException.Kind.REPLAY, "counter not increasing")
        val plain = Envelope.open(key, direction, envelope)
        last = counter // only advance after the tag verified
        return plain
    }

    @Synchronized fun lastAccepted(): Long = last
}
