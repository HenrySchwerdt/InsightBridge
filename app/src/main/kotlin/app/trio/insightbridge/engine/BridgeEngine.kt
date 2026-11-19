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
import kotlinx.serialization.json.JsonPrimitive

/** Bottom of the stack: how the engine talks to the BLE layer. */
interface LinkTransmitter {

    /** Queues all [fragments] (in order) as notifications. False if the link is gone / queue full. */
    fun send(centralId: String, generation: Long, fragments: List<ByteArray>): Boolean

    /** Largest ATT value (notification payload) usable for this central: MTU - 3, at least 20. */
    fun maxFragmentSize(centralId: String): Int

    fun disconnect(centralId: String, generation: Long)
}

/** What the UI/health layer sees of the iPhone side. */
enum class PhoneLinkState { NONE, HANDSHAKE, SESSION }

/**
 * Diagnostics sink for link lifecycle events (PROTOCOL.md section 1a). Implemented by the service to feed the
 * "connection health" statistics; the engine itself also writes every event, with its reason, to the log.
 * Callbacks run while the engine holds its lock: implementations must be quick and must not call back into it.
 */
interface LinkEventListener {

    /** A central completed an authenticated session (valid first encrypted message). [preempted] older links were closed. */
    fun onSessionEstablished(centralId: String, preempted: Int) {}

    /** A link ended. [wasSession] = it had completed an authenticated session. */
    fun onLinkClosed(centralId: String, reason: String, wasSession: Boolean, durationMs: Long) {}

    object None : LinkEventListener
}

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
    private val onPhoneActivity: () -> Unit = {},
    private val linkEvents: LinkEventListener = LinkEventListener.None,
    private val onRequestStart: () -> Unit = {},
    private val onRequestEnd: () -> Unit = {}
) : PairingController.Listener {

    companion object {

        const val SESSION_FIRST_MESSAGE_TIMEOUT_MS = 10_000L
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        /**
         * An ACTIVE link that has produced no authenticated frame for this long is a half-open or abandoned
         * connection (the central pings every 60 s, PROTOCOL.md section 1a).
         */
        const val ACTIVE_IDLE_TIMEOUT_MS = 3 * 60_000L
        /** Links that have not completed a session yet and may run their handshakes in parallel. */
        const val MAX_PARALLEL_HANDSHAKES = 3
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

    private inner class Link(val id: String, val generation: Long, val connectedAt: Long, val order: Long) {

        val reassembler = Reassembler(clock)
        var phase = Phase.HANDSHAKE
        /** When the current phase began (handshake timeout is measured from here). */
        var phaseSince = connectedAt
        /** Set once the link was dropped: late results must not be sent to it. */
        @Volatile var closed = false
        var seqOut = 0
        var sealer: SealingChannel? = null
        var opener: OpeningChannel? = null
        var sessionDeadline = 0L
        var attempt: PairAttempt? = null
        var pendingClientId: String? = null
        val inflight = AtomicInteger()
        val seenIds = LinkedHashSet<Long>()
        var lastProgressSent = 0L
        var lastAuthAt = 0L
    }

    private val lock = Any()
    private val links = LinkedHashMap<String, Link>()
    private var nextOrder = 0L
    private val exceptionHandler = CoroutineExceptionHandler { _, t -> PumpLog.e("Unhandled engine coroutine failure", t) }
    private val _phoneState = MutableStateFlow(PhoneLinkState.NONE)
    val phoneState: StateFlow<PhoneLinkState> = _phoneState.asStateFlow()

    init {
        pairing.listener = this
    }

    // ------------------------------------------------------------------ BLE callbacks

    /**
     * A central connected. [generation] identifies THIS connection (the same device may reconnect with the
     * same address): late callbacks of an older connection are ignored.
     *
     * PROTOCOL.md 1a: a newcomer is always accepted, also while a session is ACTIVE (that session may be a ghost
     * whose disconnect was never reported). Handshakes run in parallel (at most [MAX_PARALLEL_HANDSHAKES]); only a
     * newcomer that completes an authenticated session displaces older links, see [handleEncrypted].
     */
    fun onCentralConnected(centralId: String, generation: Long = 0) = guarded("connect") {
        synchronized(lock) {
            // The same device reconnecting: whatever was left of its previous connection is dead.
            links[centralId]?.let { dropLocked(it, "reconnected", disconnect = false) }
            val link = Link(centralId, generation, clock(), nextOrder++)
            links[centralId] = link
            PumpLog.i("BLE ${tag(link)} connected (${links.size} link(s), ${links.values.count { it.phase == Phase.ACTIVE }} active)")
            // Limit parallel handshakes: the oldest link that has not completed a session goes first. A link in the
            // middle of pairing (user is comparing codes) is never the victim.
            while (links.values.count { it.phase != Phase.ACTIVE } > MAX_PARALLEL_HANDSHAKES) {
                val victim = links.values.firstOrNull { it.phase != Phase.ACTIVE && it.phase != Phase.PAIRING && it !== link } ?: break
                dropLocked(victim, "too many parallel handshakes")
            }
            updatePhoneStateLocked()
        }
    }

    fun onCentralDisconnected(centralId: String, generation: Long = 0, reason: String = "disconnected") = guarded("disconnect") {
        synchronized(lock) {
            val link = links[centralId] ?: return@synchronized
            if (link.generation != generation) {
                PumpLog.d("BLE ${tag(link)} ignoring stale disconnect of generation $generation")
                return@synchronized
            }
            dropLocked(link, reason, disconnect = false)
        }
    }

    fun onFragment(centralId: String, fragment: ByteArray, generation: Long = 0) = guarded("fragment") {
        val link = synchronized(lock) { links[centralId]?.takeIf { it.generation == generation && !it.closed } } ?: return@guarded
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
                    Phase.HANDSHAKE -> if (now - link.phaseSince > HANDSHAKE_TIMEOUT_MS) dropLocked(link, "handshake timeout")
                    Phase.PAIRING -> link.attempt?.let { if (now > it.deadline) dropLocked(link, "pairing timeout") }
                    Phase.SESSION_PENDING -> if (now > link.sessionDeadline) dropLocked(link, "no valid message after session start")
                    Phase.ACTIVE -> if (now - link.lastAuthAt > ACTIVE_IDLE_TIMEOUT_MS) dropLocked(link, "idle timeout: no authenticated frame for ${(now - link.lastAuthAt) / 1000} s")
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
        val link = synchronized(lock) { links.values.lastOrNull { it.phase == Phase.ACTIVE } } ?: return
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

    private fun dropLocked(link: Link, reason: String, disconnect: Boolean = true) {
        if (link.closed) return
        link.closed = true
        val now = clock()
        val wasSession = link.phase == Phase.ACTIVE || link.lastAuthAt > 0
        PumpLog.i("BLE ${tag(link)} closed: $reason (phase=${link.phase.name.lowercase()}, session=$wasSession, up ${(now - link.connectedAt) / 1000} s)")
        if (links[link.id] === link) links.remove(link.id)
        abortAttemptLocked(link, reason)
        if (disconnect) {
            try {
                transmitter.disconnect(link.id, link.generation)
            } catch (t: Throwable) {
                PumpLog.w("disconnect failed", t)
            }
        }
        try {
            linkEvents.onLinkClosed(link.id, reason, wasSession, now - link.connectedAt)
        } catch (t: Throwable) {
            PumpLog.w("link event listener failed", t)
        }
        updatePhoneStateLocked()
    }

    /** Short, non-identifying label for logs: last two address octets plus connection generation. */
    private fun tag(link: Link) = "${link.id.takeLast(5)}#${link.generation}"

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
        link.phaseSince = clock()
        PumpLog.i("BLE ${tag(link)} pairing completed")
        pairing.succeeded()
    }

    private fun onSessionStart(link: Link, m: Handshake.SessionStart) {
        synchronized(lock) {
            if (link.phase != Phase.HANDSHAKE) {
                dropLocked(link, "unexpected sessionStart")
                return
            }
            if (m.version != RequestValidator.PROTOCOL_VERSION) {
                PumpLog.i("BLE ${tag(link)} handshake: sessionReject unsupportedProtocol")
                sendPlainRaw(link, HandshakeCodec.sessionReject("unsupportedProtocol"))
                return
            }
            val ltk = identity.ltk()
            val bound = identity.clientId()
            if (ltk == null || (bound != null && bound != m.clientId)) {
                PumpLog.i("BLE ${tag(link)} handshake: sessionReject unknownClient")
                sendPlainRaw(link, HandshakeCodec.sessionReject("unknownClient"))
                return
            }
            val nonceB = ByteArray(HandshakeCodec.NONCE_BYTES).also { random.nextBytes(it) }
            val sk = PairingCrypto.deriveSessionKey(ltk, m.nonce, nonceB)
            link.sealer = SealingChannel(sk, Envelope.DIR_BRIDGE_TO_IPHONE)
            link.opener = OpeningChannel(sk, Envelope.DIR_IPHONE_TO_BRIDGE)
            link.pendingClientId = m.clientId
            link.phase = Phase.SESSION_PENDING
            link.phaseSince = clock()
            link.sessionDeadline = clock() + SESSION_FIRST_MESSAGE_TIMEOUT_MS
            PumpLog.i("BLE ${tag(link)} handshake: sessionResp sent, waiting for first encrypted message")
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
            if (link.closed) return
            if (link.phase == Phase.SESSION_PENDING) {
                link.phase = Phase.ACTIVE
                link.phaseSince = clock()
                // Authenticated: bind the client identity if this is the first session after pairing.
                val cid = link.pendingClientId
                if (cid != null && identity.clientId() == null) {
                    try { identity.bindClientId(cid) } catch (e: Exception) { PumpLog.w("bind clientId failed", e) }
                }
                // PROTOCOL.md 1a: the paired client proved itself on this link, so every OLDER link is stale (a ghost
                // session whose disconnect was never reported, an abandoned handshake) and is closed at once. Only a
                // link in the middle of pairing is spared (the user is comparing codes; it has its own deadline), and
                // links that connected after this one keep going: if one of them authenticates it displaces this one.
                val older = links.values.filter { it !== link && it.order < link.order && it.phase != Phase.PAIRING }.toList()
                older.forEach { dropLocked(it, "preempted by newer authenticated link ${tag(link)}") }
                PumpLog.i("BLE ${tag(link)} session established (${(clock() - link.connectedAt)} ms after connect, preempted ${older.size})")
                try {
                    linkEvents.onSessionEstablished(link.id, older.size)
                } catch (t: Throwable) {
                    PumpLog.w("link event listener failed", t)
                }
                updatePhoneStateLocked()
            }
        }
        synchronized(lock) { link.lastAuthAt = clock() }
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
        // `ping` is liveness only (no pump traffic): it must be answered even when real requests are queued up.
        val isPing = (frame["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "ping"
        if (!isPing && link.inflight.incrementAndGet() > MAX_INFLIGHT_PER_LINK) {
            link.inflight.decrementAndGet()
            sendEncrypted(link, Wire.error(id, ErrorCode.BUSY, "Too many requests in flight"))
            return
        }
        onRequestStart()
        scope.launch(exceptionHandler) {
            try {
                val response = dispatcher.handle(frame)
                sendEncrypted(link, response)
            } finally {
                if (!isPing) link.inflight.decrementAndGet()
                onRequestEnd()
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
            if (link.closed) return
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
            if (link.closed) return
            synchronized(link) { transmit(link, Envelope.plaintext(json)) }
        } catch (t: Throwable) {
            PumpLog.w("send failed", t)
        }
    }

    private fun transmit(link: Link, envelope: ByteArray) {
        val max = transmitter.maxFragmentSize(link.id).coerceAtLeast(20)
        val fragments = Fragmenter.fragment(envelope, link.seqOut, max)
        link.seqOut = (link.seqOut + 1) and 0xFFFF
        if (!transmitter.send(link.id, link.generation, fragments)) {
            // Not inline: callers hold the link lock and dropLocked needs the engine lock (lock order).
            scope.launch(exceptionHandler) { synchronized(lock) { if (links[link.id] === link) dropLocked(link, "notification could not be queued (link gone or queue full)") } }
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
