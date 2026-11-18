/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import app.trio.insightbridge.backend.BackendEvent
import app.trio.insightbridge.backend.SimulatedPump
import app.trio.insightbridge.protocol.EcdhP256
import app.trio.insightbridge.protocol.Envelope
import app.trio.insightbridge.protocol.Fragmenter
import app.trio.insightbridge.protocol.Handshake
import app.trio.insightbridge.protocol.HandshakeCodec
import app.trio.insightbridge.protocol.OpeningChannel
import app.trio.insightbridge.protocol.PairingCrypto
import app.trio.insightbridge.protocol.Reassembler
import app.trio.insightbridge.protocol.ReassemblyResult
import app.trio.insightbridge.protocol.RequestParser
import app.trio.insightbridge.protocol.SealingChannel
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** In-memory BLE: collects what the bridge sends and records disconnects. */
class FakeLink : LinkTransmitter {

    val toCentral = HashMap<String, ArrayDeque<ByteArray>>()
    val disconnected = ArrayList<String>()
    var maxFragment = 20
    var accept = true

    val disconnectGenerations = ArrayList<Pair<String, Long>>()

    override fun send(centralId: String, generation: Long, fragments: List<ByteArray>): Boolean {
        if (!accept) return false
        toCentral.getOrPut(centralId) { ArrayDeque() }.addAll(fragments)
        return true
    }

    override fun maxFragmentSize(centralId: String) = maxFragment
    override fun disconnect(centralId: String, generation: Long) { disconnected.add(centralId); disconnectGenerations.add(centralId to generation) }
}

/** Everything wired together exactly like [app.trio.insightbridge.service.BridgeCore], minus Android. */
@OptIn(ExperimentalCoroutinesApi::class)
class Rig(val scope: TestScope, val bridgeVersion: String = "test", maxStoreEntries: Int = 5000) {

    var now = 1_800_000_000_000L
    val clock: () -> Long = { now }
    val sim = SimulatedPump(clock)
    val link = FakeLink()
    val identity = MemoryIdentityStore()
    val pairing = PairingController(clock)
    val idempotencyFile = MemoryTextFile()
    var store = IdempotencyStore(idempotencyFile, clock, maxEntries = maxStoreEntries)
    var phoneActivity = 0
    var requestsSeen = ArrayList<String>()
    val dispatcher = OpDispatcher(
        backend = { sim }, store = store, clock = clock, bridgeVersion = bridgeVersion,
        onRequest = { requestsSeen.add(it) }
    )
    val engine = BridgeEngine(scope, clock, identity, pairing, dispatcher, link, SecureRandom(), onPhoneActivity = { phoneActivity++ })
    val broadcast = ArrayList<JsonObject>()

    init {
        app.aaps.pump.insight.utils.PumpLog.sink = object : app.aaps.pump.insight.utils.PumpLog.Sink {
            override fun log(level: app.aaps.pump.insight.utils.PumpLog.Level, message: String, throwable: Throwable?) { System.err.println("[bridge] $level $message ${throwable ?: ""}") }
        }
        // same forwarding as BridgeCore
        scope.backgroundScope.launch { sim.events.collect { e: BackendEvent -> val (wire, key) = EventMapper.toWire(e); engine.broadcastEvent(wire, key) } }
        scope.testScheduler.runCurrent() // subscribe the collector before any event can be emitted
    }

    /** advanceUntilIdle ignores background tasks, so also run the current ones (the event forwarder lives there). */
    fun idle() { repeat(3) { scope.advanceUntilIdle(); scope.testScheduler.runCurrent() } }

    fun central(id: String = "AA:00") = FakeCentral(id, this).also { link.toCentral.remove(id); link.disconnected.remove(id); engine.onCentralConnected(id) }

    /** Pairs a fresh iPhone and returns it in an established session. */
    fun pairedSession(id: String = "AA:00", clientId: String = "0123456789abcdef"): FakeCentral {
        val c = central(id)
        pairing.open()
        c.pair(clientId)
        check(c.takeHandshake()!!["t"]!!.jsonPrimitive.content == "pairDone")
        engine.onCentralDisconnected(id)
        val c2 = central(id)
        c2.ltk = c.ltk; c2.clientId = c.clientId
        c2.startSession()
        return c2
    }
}

/** A minimal iOS-side implementation of the protocol, used to drive the bridge in tests. */
class FakeCentral(val id: String, val rig: Rig) {

    var ltk: ByteArray = ByteArray(0)
    var clientId = "0123456789abcdef"
    var sealer: SealingChannel? = null
    var opener: OpeningChannel? = null
    var sasSeen: String? = null
    private val reassembler = Reassembler(rig.clock)
    private var seq = 0
    private var nextId = 1L
    val envelopes = ArrayList<ByteArray>()
    val events = ArrayList<JsonObject>()
    val handshakeReplies = ArrayList<JsonObject>()

    fun isDisconnected() = id in rig.link.disconnected

    fun sendEnvelope(env: ByteArray) {
        val frags = Fragmenter.fragment(env, seq++, rig.link.maxFragment)
        frags.forEach { rig.engine.onFragment(id, it) }
    }

    fun sendHandshake(json: ByteArray) = sendEnvelope(Envelope.plaintext(json))

    /** Collects everything the bridge has sent so far. */
    fun drain() {
        val q = rig.link.toCentral[id] ?: return
        while (q.isNotEmpty()) {
            when (val r = reassembler.feed(q.removeFirst())) {
                is ReassemblyResult.Message -> envelopes.add(r.bytes)
                else -> Unit
            }
        }
    }

    /** Plaintext handshake replies since the last call. */
    fun takeHandshake(): JsonObject? {
        drain()
        val i = envelopes.indexOfFirst { it[0].toInt() == Envelope.TYPE_PLAINTEXT }
        if (i < 0) return null
        val body = envelopes.removeAt(i)
        return RequestParser.parseObject(body.copyOfRange(1, body.size))
    }

    /**
     * iOS side of PROTOCOL.md 4.1 (v2): commit, pairResp, reveal, compare, confirm.
     * [tamperReveal] sends a reveal that does not match the commitment.
     */
    fun pair(
        clientId: String = this.clientId, confirmMacOverride: ByteArray? = null, userConfirms: Boolean = true,
        tamperReveal: Boolean = false, version: Int = 2
    ) {
        this.clientId = clientId
        val kp = EcdhP256.generate()
        val nonceI = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sendHandshake(HandshakeCodec.pairStart(PairingCrypto.commitment(kp.publicKey, nonceI), clientId, version))
        val resp = takeHandshake() ?: error("no pairResp")
        check(resp["t"]!!.jsonPrimitive.content == "pairResp") { "got $resp" }
        val pubB = java.util.Base64.getDecoder().decode(resp["pub"]!!.jsonPrimitive.content)
        val nonceB = java.util.Base64.getDecoder().decode(resp["nonce"]!!.jsonPrimitive.content)
        val revealNonce = if (tamperReveal) nonceI.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } else nonceI
        sendHandshake(HandshakeCodec.pairReveal(kp.publicKey, revealNonce))
        if (tamperReveal) return
        val z = EcdhP256.sharedSecret(kp.privateKey, pubB)
        ltk = PairingCrypto.deriveLtk(z, nonceI, nonceB)
        sasSeen = PairingCrypto.sas(ltk)
        sendHandshake(HandshakeCodec.pairConfirm(confirmMacOverride ?: PairingCrypto.confirmMacInitiator(ltk)))
        if (userConfirms) rig.pairing.userConfirm()
    }

    fun startSession(): JsonObject {
        val nonceI = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sendHandshake(HandshakeCodec.sessionStart(clientId, nonceI))
        val resp = takeHandshake() ?: error("no session reply")
        if (resp["t"]!!.jsonPrimitive.content != "sessionResp") return resp
        val nonceB = java.util.Base64.getDecoder().decode(resp["nonce"]!!.jsonPrimitive.content)
        val mac = java.util.Base64.getDecoder().decode(resp["mac"]!!.jsonPrimitive.content)
        check(mac.contentEquals(PairingCrypto.sessionMac(ltk, nonceI, nonceB))) { "bridge MAC invalid" }
        val sk = PairingCrypto.deriveSessionKey(ltk, nonceI, nonceB)
        sealer = SealingChannel(sk, Envelope.DIR_IPHONE_TO_BRIDGE)
        opener = OpeningChannel(sk, Envelope.DIR_BRIDGE_TO_IPHONE)
        return resp
    }

    fun sendRaw(json: String) = sendEnvelope(sealer!!.seal(json.toByteArray()))

    /** Opens everything received so far; responses are returned, events collected. */
    fun readEncrypted(): List<JsonObject> {
        drain()
        val out = ArrayList<JsonObject>()
        val it = envelopes.iterator()
        while (it.hasNext()) {
            val env = it.next()
            if (env[0].toInt() != Envelope.TYPE_ENCRYPTED) continue
            it.remove()
            val obj = RequestParser.parseObject(opener!!.open(env))
            if (obj.containsKey("evt")) { events.add(obj); rig.broadcast.add(obj) } else out.add(obj)
        }
        return out
    }

    /** Sends a request and returns its response (runs the test scheduler). */
    fun request(op: String, args: String = "{}", id: Long = nextId++): JsonObject {
        sendRaw("""{"id":$id,"op":"$op","args":$args}""")
        rig.idle()
        val r = readEncrypted().firstOrNull { it["id"]!!.jsonPrimitive.content.toLong() == id }
        return r ?: error("no response to $op")
    }

    fun ok(r: JsonObject) = r["ok"]!!.jsonPrimitive.content == "true"
    fun result(r: JsonObject) = r["result"]!!.jsonObject
    fun error(r: JsonObject) = r["error"]!!.jsonObject
    fun code(r: JsonObject) = error(r)["code"]!!.jsonPrimitive.content
    fun uncertain(r: JsonObject) = error(r)["uncertain"]!!.jsonPrimitive.content == "true"
}

@Suppress("unused") private val unused: CoroutineScope? = null
@Suppress("unused") private val h: Handshake? = null
