/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import app.trio.insightbridge.protocol.EcdhP256
import app.trio.insightbridge.protocol.Envelope
import app.trio.insightbridge.protocol.EnvelopeException
import app.trio.insightbridge.protocol.ErrorCode
import app.trio.insightbridge.protocol.Fragmenter
import app.trio.insightbridge.protocol.Framing
import app.trio.insightbridge.protocol.Handshake
import app.trio.insightbridge.protocol.HandshakeCodec
import app.trio.insightbridge.protocol.Kdf
import app.trio.insightbridge.protocol.OpeningChannel
import app.trio.insightbridge.protocol.PairingCrypto
import app.trio.insightbridge.protocol.Reassembler
import app.trio.insightbridge.protocol.ReassemblyResult
import app.trio.insightbridge.protocol.RequestParser
import app.trio.insightbridge.protocol.SealingChannel
import app.trio.insightbridge.protocol.UnparseableFrame
import app.trio.insightbridge.protocol.Wire
import app.aaps.pump.insight.utils.PumpLog
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/** Bottom of the stack: how the engine talks to the BLE layer. */
interface LinkTransmitter {

    /** Queues all [fragments] (in order) as notifications. False if the link is gone / queue full. */
    fun send(centralId: String, fragments: List<ByteArray>): Boolean

    /** Largest ATT value (notification payload) usable for this central: MTU - 3, at least 20. */
    fun maxFragmentSize(centralId: String): Int

    fun disconnect(centralId: String)
}

/** What the UI/health layer sees of the iPhone side. */
enum class PhoneLinkState { NONE, HANDSHAKE, SESSION }

/**
 * Transport independent bridge protocol engine: fragments -> envelope -> handshake / session ->
 * [OpDispatcher]. All BLE specifics are behind [LinkTransmitter] so the whole pipeline runs in JVM tests.
 *
 * Threading: state is guarded by [lock]; requests run on [scope] (supervised, every child has an
 * exception handler). All retained state per link is bounded.
 */
class BridgeEngine(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val identity: IdentityStore,
    private val pairing: PairingController,
    private val dispatcher: OpDispatcher,
    private val transmitter: LinkTransmitter,
    private val random: SecureRandom = SecureRandom(),
    private val onPhoneActivity: () -> Unit = {}
) : PairingController.Listener {

    companion object {

        const val SESSION_FIRST_MESSAGE_TIMEOUT_MS = 10_000L
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val MAX_INFLIGHT_PER_LINK = 8
        const val MAX_REMEMBERED_IDS = 1024
        const val PROGRESS_MIN_INTERVAL_MS = 2_000L
    }

    private enum class Phase { HANDSHAKE, PAIRING, SESSION_PENDING, ACTIVE }

    /** One pairing attempt (PROTOCOL.md v2: commit -> reveal -> compare codes -> confirm). */
    private class PairAttempt(
        val commit: ByteArray, val clientId: String?, val deadline: Long,
        val bridgePrivate: PrivateKey, val bridgePublic: ByteArray, val nonceB: ByteArray
    ) {

        var ltk: ByteArray? = null
        var phoneConfirmed = false
        var userAccepted: Boolean? = null
        val revealed: Boolean get() = ltk != null
    }

    private inner class Link(val id: String, val connectedAt: Long) {

        val reassembler = Reassembler(clock)
        var phase = Phase.HANDSHAKE
        var seqOut = 0
        var sealer: SealingChannel? = null
        var opener: OpeningChannel? = null
        var sessionDeadline = 0L
        var attempt: PairAttempt? = null
        var rejectBusy = false
        var pendingClientId: String? = null
        val inflight = AtomicInteger()
        val seenIds = LinkedHashSet<Long>()
        var lastProgressSent = 0L
    }

    private val lock = Any()
    private val links = LinkedHashMap<String, Link>()
    private val exceptionHandler = CoroutineExceptionHandler { _, t -> PumpLog.e("Unhandled engine coroutine failure", t) }
    private val _phoneState = MutableStateFlow(PhoneLinkState.NONE)
    val phoneState: StateFlow<PhoneLinkState> = _phoneState.asStateFlow()

    init {
        pairing.listener = this
    }

    // ------------------------------------------------------------------ BLE callbacks

    fun onCentralConnected(centralId: String) = guarded("connect") {
        synchronized(lock) {
            val link = Link(centralId, clock())
            val active = links.values.firstOrNull { it.phase == Phase.ACTIVE && it.id != centralId }
            if (active != null) {
                link.rejectBusy = true
            } else {
                // A newer central replaces anything that has not completed a session.
                links.values.filter { it.id != centralId }.toList().forEach { dropLocked(it, "replaced by newer central") }
            }
            links[centralId] = link
            updatePhoneStateLocked()
        }
    }

    fun onCentralDisconnected(centralId: String) = guarded("disconnect") {
        synchronized(lock) {
            val link = links.remove(centralId) ?: return@synchronized
            abortAttemptLocked(link, "disconnected")
            updatePhoneStateLocked()
        }
    }

    fun onFragment(centralId: String, fragment: ByteArray) = guarded("fragment") {
        val link = synchronized(lock) { links[centralId] } ?: return@guarded
        if (link.rejectBusy) {
            sendPlainRaw(link, HandshakeCodec.error(ErrorCode.BUSY, "Another device is connected"))
            synchronized(lock) { dropLocked(link, "busy") }
            return@guarded
        }
        when (val r = link.reassembler.feed(fragment)) {
            is ReassemblyResult.Incomplete -> Unit
            is ReassemblyResult.Dropped -> PumpLog.d("fragment dropped: ${r.reason}")
            is ReassemblyResult.TooLarge -> sendTooLarge(link)
            is ReassemblyResult.Message -> handleEnvelope(link, r.bytes)
        }
    }

    /** Called about once a second. */
    fun tick() = guarded("tick") {
        val now = clock()
        pairing.tick()
        synchronized(lock) {
            for (link in links.values.toList()) {
                link.reassembler.expire()
                when (link.phase) {
                    Phase.HANDSHAKE -> if (now - link.connectedAt > HANDSHAKE_TIMEOUT_MS) dropLocked(link, "handshake timeout")
                    Phase.PAIRING -> link.attempt?.let { if (now > it.deadline) dropLocked(link, "pairing timeout") }
                    Phase.SESSION_PENDING -> if (now > link.sessionDeadline) dropLocked(link, "no valid message after session start")
                    Phase.ACTIVE -> Unit
                }
            }
        }
    }

    /** Retained per-link state (ids remembered, links, partial buffers): used by the soak test to prove it stays bounded. */
    internal fun retainedStateSize(): Int = synchronized(lock) {
        links.values.sumOf { l -> synchronized(l.seenIds) { l.seenIds.size } + (if (l.reassembler.hasPartial) 1 else 0) + l.inflight.get() } + links.size
    }

    // ------------------------------------------------------------------ events to phone

    /** Sends an unsolicited event to the active phone (no-op without one). */
    fun broadcastEvent(event: JsonObject, throttleKey: String? = null) {
        val link = synchronized(lock) { links.values.firstOrNull { it.phase == Phase.ACTIVE } } ?: return
        if (throttleKey != null) {
            val now = clock()
            if (now - link.lastProgressSent < PROGRESS_MIN_INTERVAL_MS) return
            link.lastProgressSent = now
        }
        sendEncrypted(link, event)
    }

    fun disconnectPhone() = guarded("disconnectPhone") {
        synchronized(lock) { links.values.toList().forEach { dropLocked(it, "requested") } }
    }

    // ------------------------------------------------------------------ internals

    private fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            PumpLog.e("Engine failure in $what", t)
        }
    }

    private fun updatePhoneStateLocked() {
        _phoneState.value = when {
            links.values.any { it.phase == Phase.ACTIVE } -> PhoneLinkState.SESSION
            links.isNotEmpty() -> PhoneLinkState.HANDSHAKE
            else -> PhoneLinkState.NONE
        }
    }

    private fun dropLocked(link: Link, reason: String) {
        PumpLog.i("Dropping central link: $reason")
        links.remove(link.id)
        abortAttemptLocked(link, reason)
        try {
            transmitter.disconnect(link.id)
        } catch (t: Throwable) {
            PumpLog.w("disconnect failed", t)
        }
        updatePhoneStateLocked()
    }

    private fun abortAttemptLocked(link: Link, reason: String) {
        if (link.attempt != null) {
            link.attempt = null
            pairing.attemptAborted(reason)
        }
    }

    private fun sendTooLarge(link: Link) {
        if (link.sealer != null) {
            sendEncrypted(link, Wire.event("error", Wire.toJson(mapOf("code" to ErrorCode.TOO_LARGE.wire))))
        } else {
            sendPlainRaw(link, HandshakeCodec.error(ErrorCode.TOO_LARGE, "Message too large"))
        }
    }

    private fun handleEnvelope(link: Link, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        when (bytes[0].toInt() and 0xFF) {
            Envelope.TYPE_PLAINTEXT -> handleHandshake(link, bytes.copyOfRange(1, bytes.size))
            Envelope.TYPE_ENCRYPTED -> handleEncrypted(link, bytes)
            else -> synchronized(lock) { dropLocked(link, "unknown envelope type") }
        }
    }

    // ---- handshake ----

    private fun handleHandshake(link: Link, body: ByteArray) {
        // Plaintext is only legal before a session exists. Never answer it (not even with a reject) afterwards.
        if (link.phase == Phase.SESSION_PENDING || link.phase == Phase.ACTIVE) {
            synchronized(lock) { dropLocked(link, "plaintext after session start") }
            return
        }
        val msg = try {
            HandshakeCodec.parse(body)
        } catch (e: HandshakeCodec.Invalid) {
            PumpLog.d("invalid handshake: ${e.message}")
            return
        }
        when (msg) {
            is Handshake.PairStart -> onPairStart(link, msg)
            is Handshake.PairReveal -> onPairReveal(link, msg)
            is Handshake.PairConfirm -> onPairConfirm(link, msg)
            is Handshake.SessionStart -> onSessionStart(link, msg)
            is Handshake.Unknown -> PumpLog.d("ignoring handshake type")
        }
    }

    private fun onPairStart(link: Link, m: Handshake.PairStart) {
        synchronized(lock) {
            if (m.version != RequestValidator.PROTOCOL_VERSION) {
                sendPlainRaw(link, HandshakeCodec.pairReject("unsupportedProtocol"))
                return
            }
            if (link.phase != Phase.HANDSHAKE || !pairing.beginAttempt()) {
                // no window, or this window's single attempt was already used
                sendPlainRaw(link, HandshakeCodec.pairReject("notInPairingMode"))
                return
            }
            val kp = EcdhP256.generate(random)
            val nonceB = ByteArray(HandshakeCodec.NONCE_BYTES).also { random.nextBytes(it) }
            link.attempt = PairAttempt(m.commit, m.clientId, clock() + PairingController.WINDOW_MS, kp.privateKey, kp.publicKey, nonceB)
            link.phase = Phase.PAIRING
            sendPlainRaw(link, HandshakeCodec.pairResp(kp.publicKey, nonceB, hex(identity.bridgeId)))
        }
    }

    private fun abortPairing(link: Link, reason: String, wireReason: String) {
        sendPlainRaw(link, HandshakeCodec.pairReject(wireReason))
        link.attempt = null
        pairing.attemptAborted(reason)
        dropLocked(link, "pairing aborted: $reason")
    }

    private fun onPairReveal(link: Link, m: Handshake.PairReveal) {
        synchronized(lock) {
            val attempt = link.attempt
            if (link.phase != Phase.PAIRING || attempt == null || attempt.revealed) {
                sendPlainRaw(link, HandshakeCodec.pairReject("notInPairingMode"))
                return
            }
            // The commitment fixed the iPhone's values before it saw ours.
            if (!Kdf.constantTimeEquals(PairingCrypto.commitment(m.pub, m.nonce), attempt.commit)) {
                abortPairing(link, "commitMismatch", "commitMismatch")
                return
            }
            val z = try {
                EcdhP256.sharedSecret(attempt.bridgePrivate, m.pub)
            } catch (e: Exception) {
                abortPairing(link, "invalidKey", "invalidKey")
                return
            }
            val ltk = PairingCrypto.deriveLtk(z, m.nonce, attempt.nonceB)
            z.fill(0)
            attempt.ltk = ltk
            pairing.showCode(PairingCrypto.sas(ltk))
        }
    }

    private fun onPairConfirm(link: Link, m: Handshake.PairConfirm) {
        synchronized(lock) {
            val attempt = link.attempt
            val ltk = attempt?.ltk
            if (link.phase != Phase.PAIRING || attempt == null || ltk == null) {
                sendPlainRaw(link, HandshakeCodec.pairReject("notInPairingMode"))
                return
            }
            if (!Kdf.constantTimeEquals(m.mac, PairingCrypto.confirmMacInitiator(ltk))) {
                abortPairing(link, "mismatch", "badMac")
                return
            }
            attempt.phoneConfirmed = true
            pairing.phoneConfirmed()
            completePairingIfReadyLocked(link)
        }
    }

    override fun onUserDecision(accepted: Boolean) = guarded("userDecision") {
        synchronized(lock) {
            val link = links.values.firstOrNull { it.attempt != null } ?: return@synchronized
            val attempt = link.attempt!!
            if (!accepted) {
                sendPlainRaw(link, HandshakeCodec.pairReject("userRejected"))
                link.attempt = null
                dropLocked(link, "user rejected pairing")
                return@synchronized
            }
            if (!attempt.revealed) return@synchronized // nothing to confirm yet
            attempt.userAccepted = true
            completePairingIfReadyLocked(link)
        }
    }

    private fun completePairingIfReadyLocked(link: Link) {
        val attempt = link.attempt ?: return
        val ltk = attempt.ltk ?: return
        if (!attempt.phoneConfirmed || attempt.userAccepted != true) return
        // Both devices confirmed: persist first, then tell the phone.
        try {
            // Pairing a new iPhone replaces the previous one.
            links.values.filter { it.id != link.id && it.phase == Phase.ACTIVE }.toList().forEach { dropLocked(it, "pairing replaced") }
            identity.savePairing(ltk, attempt.clientId)
        } catch (e: Exception) {
            PumpLog.e("Could not store pairing", e)
            sendPlainRaw(link, HandshakeCodec.pairReject("internal"))
            link.attempt = null
            pairing.failed("storage")
            dropLocked(link, "storage failure")
            return
        }
        sendPlainRaw(link, HandshakeCodec.pairDone(PairingCrypto.confirmMacBridge(ltk)))
        link.attempt = null
        link.phase = Phase.HANDSHAKE
        pairing.succeeded()
    }

    private fun onSessionStart(link: Link, m: Handshake.SessionStart) {
        synchronized(lock) {
            if (link.phase != Phase.HANDSHAKE) {
                dropLocked(link, "unexpected sessionStart")
                return
            }
            if (m.version != RequestValidator.PROTOCOL_VERSION) {
                sendPlainRaw(link, HandshakeCodec.sessionReject("unsupportedProtocol"))
                return
            }
            val ltk = identity.ltk()
            val bound = identity.clientId()
            if (ltk == null || (bound != null && bound != m.clientId)) {
                sendPlainRaw(link, HandshakeCodec.sessionReject("unknownClient"))
                return
            }
            val nonceB = ByteArray(HandshakeCodec.NONCE_BYTES).also { random.nextBytes(it) }
            val sk = PairingCrypto.deriveSessionKey(ltk, m.nonce, nonceB)
            link.sealer = SealingChannel(sk, Envelope.DIR_BRIDGE_TO_IPHONE)
            link.opener = OpeningChannel(sk, Envelope.DIR_IPHONE_TO_BRIDGE)
            link.pendingClientId = m.clientId
            link.phase = Phase.SESSION_PENDING
            link.sessionDeadline = clock() + SESSION_FIRST_MESSAGE_TIMEOUT_MS
            sendPlainRaw(link, HandshakeCodec.sessionResp(hex(identity.bridgeId), nonceB, PairingCrypto.sessionMac(ltk, m.nonce, nonceB)))
            sk.fill(0)
            ltk.fill(0)
        }
    }

    // ---- encrypted application messages ----

    private fun handleEncrypted(link: Link, envelope: ByteArray) {
        val opener = link.opener
        val plain = try {
            if (opener == null || (link.phase != Phase.SESSION_PENDING && link.phase != Phase.ACTIVE)) {
                throw EnvelopeException(EnvelopeException.Kind.MALFORMED, "no session")
            }
            opener.open(envelope)
        } catch (e: EnvelopeException) {
            // Replay, bad tag or no session: drop the connection (PROTOCOL.md section 3).
            synchronized(lock) { dropLocked(link, "rejected envelope (${e.kind})") }
            return
        }
        synchronized(lock) {
            if (link.phase == Phase.SESSION_PENDING) {
                link.phase = Phase.ACTIVE
                // Authenticated: bind the client identity if this is the first session after pairing.
                val cid = link.pendingClientId
                if (cid != null && identity.clientId() == null) {
                    try { identity.bindClientId(cid) } catch (e: Exception) { PumpLog.w("bind clientId failed", e) }
                }
                // Anything else that has not finished a session is replaced by this one.
                links.values.filter { it.id != link.id && it.phase != Phase.ACTIVE }.toList().forEach { dropLocked(it, "superseded") }
                updatePhoneStateLocked()
            }
        }
        onPhoneActivity()
        val frame = try {
            RequestParser.parseObject(plain)
        } catch (e: UnparseableFrame) {
            PumpLog.d("authenticated but unparseable frame ignored")
            return
        }
        val id = RequestParser.idOf(frame)
        if (id == null) {
            PumpLog.d("frame without valid id ignored")
            return
        }
        if (!rememberId(link, id)) {
            sendEncrypted(link, Wire.error(id, ErrorCode.INVALID_ARGS, "request id was already used in this session"))
            return
        }
        if (link.inflight.incrementAndGet() > MAX_INFLIGHT_PER_LINK) {
            link.inflight.decrementAndGet()
            sendEncrypted(link, Wire.error(id, ErrorCode.BUSY, "Too many requests in flight"))
            return
        }
        scope.launch(exceptionHandler) {
            try {
                val response = dispatcher.handle(frame)
                sendEncrypted(link, response)
            } finally {
                link.inflight.decrementAndGet()
            }
        }
    }

    private fun rememberId(link: Link, id: Long): Boolean = synchronized(link.seenIds) {
        if (!link.seenIds.add(id)) return false
        if (link.seenIds.size > MAX_REMEMBERED_IDS) {
            val it = link.seenIds.iterator()
            it.next(); it.remove()
        }
        true
    }

    // ---- sending ----

    private fun sendEncrypted(link: Link, obj: JsonObject) {
        try {
            synchronized(link) {
                val sealer = link.sealer ?: return
                val envelope = sealer.seal(Wire.encode(obj))
                transmit(link, envelope)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            PumpLog.w("send failed", t)
        }
    }

    private fun sendPlainRaw(link: Link, json: ByteArray) {
        try {
            synchronized(link) { transmit(link, Envelope.plaintext(json)) }
        } catch (t: Throwable) {
            PumpLog.w("send failed", t)
        }
    }

    private fun transmit(link: Link, envelope: ByteArray) {
        val max = transmitter.maxFragmentSize(link.id).coerceAtLeast(20)
        val fragments = Fragmenter.fragment(envelope, link.seqOut, max)
        link.seqOut = (link.seqOut + 1) and 0xFFFF
        if (!transmitter.send(link.id, fragments)) {
            // Not inline: callers hold the link lock and dropLocked needs the engine lock (lock order).
            scope.launch(exceptionHandler) { synchronized(lock) { if (links[link.id] === link) dropLocked(link, "transmit queue full or link gone") } }
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
