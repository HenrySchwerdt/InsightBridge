package app.trio.insightbridge.engine

import app.trio.insightbridge.protocol.Envelope
import app.trio.insightbridge.protocol.HandshakeCodec
import app.trio.insightbridge.protocol.SealingChannel
import app.trio.insightbridge.protocol.Wire
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * PROTOCOL.md 1a, bridge side: a ghost ACTIVE link must never lock the real iPhone out, handshakes run in parallel,
 * only an authenticated newcomer displaces older links, idle links time out, ping keeps a link alive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkRecoveryTest {

    private class Recorder : LinkEventListener {

        val sessions = ArrayList<Int>()
        val closed = ArrayList<Triple<String, String, Boolean>>()
        override fun onSessionEstablished(centralId: String, preempted: Int) { sessions.add(preempted) }
        override fun onLinkClosed(centralId: String, reason: String, wasSession: Boolean, durationMs: Long) { closed.add(Triple(centralId, reason, wasSession)) }
    }

    private val hello = """{"protocol":2}"""

    /** A second connection of the same paired iPhone (new address, as after a rotation), session started. */
    private fun Rig.second(base: FakeCentral, id: String): FakeCentral {
        val c = central(id)
        c.ltk = base.ltk
        c.clientId = base.clientId
        c.startSession()
        return c
    }

    private fun Rig.activeGhost(id: String = "AA:00"): FakeCentral {
        val g = pairedSession(id)
        assertThat(g.ok(g.request("hello", hello))).isTrue()
        return g
    }

    // ------------------------------------------------------------------ ghost link vs. reconnecting iPhone

    @Test fun newcomerThatAuthenticatesPreemptsAGhostActiveLinkImmediately() = runTest {
        val events = Recorder()
        val rig = Rig(this, linkEvents = events)
        val ghost = rig.activeGhost()
        val phone = rig.second(ghost, "BB:11")

        // handshake done, but nothing authenticated yet: the ghost is untouched
        assertThat(ghost.isDisconnected()).isFalse()
        assertThat(phone.ok(phone.request("hello", hello))).isTrue() // valid first encrypted message

        assertThat(ghost.isDisconnected()).isTrue()
        assertThat(phone.isDisconnected()).isFalse()
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.SESSION)
        assertThat(events.sessions.last()).isEqualTo(1)
        assertThat(events.closed.any { it.second.startsWith("preempted by newer authenticated link") && it.third }).isTrue()
    }

    @Test fun eventsAreDeliveredToTheNewLinkOnly() = runTest {
        val rig = Rig(this)
        val ghost = rig.activeGhost()
        val phone = rig.second(ghost, "BB:11")
        phone.request("hello", hello)
        ghost.drain(); ghost.envelopes.clear(); rig.link.toCentral["AA:00"]?.clear()

        rig.engine.broadcastEvent(Wire.event("probe", JsonObject(emptyMap())))
        phone.readEncrypted()
        assertThat(phone.events.map { it["evt"]!!.jsonPrimitive.content }).contains("probe")
        assertThat(rig.link.toCentral["AA:00"]?.isEmpty() ?: true).isTrue()
    }

    @Test fun resultsOfARequestStillRunningOnAPreemptedLinkAreNotSentToIt() = runTest {
        val rig = Rig(this)
        val ghost = rig.activeGhost()
        ghost.drain(); ghost.envelopes.clear(); rig.link.toCentral["AA:00"]?.clear()
        ghost.sendRaw("""{"id":50,"op":"getStatus","args":{}}""") // queued, not run yet
        val phone = rig.second(ghost, "BB:11")
        phone.request("hello", hello) // runs the scheduler; the ghost was dropped before its request completed
        assertThat(ghost.isDisconnected()).isTrue()
        assertThat(rig.link.toCentral["AA:00"]?.isEmpty() ?: true).isTrue()
    }

    @Test fun lateCallbacksOfAPreemptedLinkAreIgnored() = runTest {
        val rig = Rig(this)
        val ghost = rig.activeGhost()
        val phone = rig.second(ghost, "BB:11")
        phone.request("hello", hello)
        // the Bluetooth stack finally reports the ghost's disconnect and some late data: nothing happens to the live link
        rig.engine.onCentralDisconnected("AA:00", generation = 0, reason = "late")
        rig.engine.onFragment("AA:00", byteArrayOf(0x03, 0, 1, 9))
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.SESSION)
        assertThat(phone.ok(phone.request("getStatus"))).isTrue()
    }

    @Test fun sameAddressReconnectingGetsAFreshLinkAndTheOldGenerationCannotKillIt() = runTest {
        val rig = Rig(this)
        rig.engine.onCentralConnected("AA:00", generation = 1)
        rig.engine.onCentralConnected("AA:00", generation = 2)
        rig.engine.onCentralDisconnected("AA:00", generation = 1, reason = "late")
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.HANDSHAKE)
    }

    // ------------------------------------------------------------------ unauthenticated newcomers

    @Test fun newcomerWithTheWrongClientIdCannotDisplaceAnything() = runTest {
        val rig = Rig(this)
        val ghost = rig.activeGhost()
        val intruder = rig.central("EE:66")
        intruder.sendHandshake(HandshakeCodec.sessionStart("ffffffffffffffff", ByteArray(16)))
        assertThat(intruder.takeHandshake()!!["reason"]!!.jsonPrimitive.content).isEqualTo("unknownClient")
        assertThat(ghost.isDisconnected()).isFalse()
        assertThat(ghost.ok(ghost.request("getStatus"))).isTrue()
    }

    @Test fun newcomerThatNeverSendsAValidEncryptedMessageIsDroppedAndTheOldLinkSurvives() = runTest {
        val rig = Rig(this)
        val ghost = rig.activeGhost()
        val phone = rig.second(ghost, "BB:11")
        // right handshake, wrong key: the first encrypted message cannot authenticate
        phone.sealer = SealingChannel(ByteArray(32) { 7 }, Envelope.DIR_IPHONE_TO_BRIDGE)
        phone.sendRaw("""{"id":1,"op":"hello","args":$hello}""")
        assertThat(phone.isDisconnected()).isTrue()
        assertThat(ghost.isDisconnected()).isFalse()
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.SESSION)
    }

    @Test fun newcomerThatStaysSilentAfterTheHandshakeTimesOutWithoutTouchingTheOldLink() = runTest {
        val rig = Rig(this)
        val ghost = rig.activeGhost()
        val silent = rig.second(ghost, "BB:11")
        rig.now += 11_000
        rig.engine.tick()
        assertThat(silent.isDisconnected()).isTrue()
        assertThat(ghost.isDisconnected()).isFalse() // only the 3 minute idle rule can end it
    }

    // ------------------------------------------------------------------ parallel handshakes

    @Test fun handshakesRunInParallelUpToThreeAndTheOldestPendingOneIsDroppedForAFourth() = runTest {
        val rig = Rig(this)
        val seed = rig.activeGhost("S0")
        val c1 = rig.second(seed, "C1")
        val c2 = rig.second(seed, "C2")
        val c3 = rig.second(seed, "C3")
        listOf(c1, c2, c3).forEach { assertThat(it.isDisconnected()).isFalse() }
        val c4 = rig.second(seed, "C4")
        assertThat(c1.isDisconnected()).isTrue()
        listOf(seed, c2, c3, c4).forEach { assertThat(it.isDisconnected()).isFalse() }
    }

    @Test fun onlyOlderLinksAreClosedWhenALinkAuthenticates() = runTest {
        val rig = Rig(this)
        val seed = rig.activeGhost("S0")
        val c2 = rig.second(seed, "C2")
        val c3 = rig.second(seed, "C3")
        val c4 = rig.second(seed, "C4")

        assertThat(c3.ok(c3.request("hello", hello))).isTrue()
        assertThat(seed.isDisconnected()).isTrue()
        assertThat(c2.isDisconnected()).isTrue()
        assertThat(c4.isDisconnected()).isFalse() // connected later and still handshaking: keeps its chance

        assertThat(c4.ok(c4.request("hello", hello))).isTrue()
        assertThat(c3.isDisconnected()).isTrue() // last authenticated link wins
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.SESSION)
    }

    @Test fun aLinkInTheMiddleOfPairingIsNotPreemptedByASessionOfTheOldPhone() = runTest {
        val rig = Rig(this)
        val old = rig.activeGhost("S0")
        val pairing = rig.central("P1")
        rig.pairing.open()
        pairing.pair(userConfirms = false) // codes shown, waiting for the user
        val reconnect = rig.second(old, "R2")
        assertThat(reconnect.ok(reconnect.request("hello", hello))).isTrue()
        assertThat(pairing.isDisconnected()).isFalse()
    }

    @Test fun handshakeTimesOutAfterTenSeconds() = runTest {
        val rig = Rig(this)
        val c = rig.central("AA:00")
        rig.now += 9_000
        rig.engine.tick()
        assertThat(c.isDisconnected()).isFalse()
        rig.now += 1_500
        rig.engine.tick()
        assertThat(c.isDisconnected()).isTrue()
    }

    // ------------------------------------------------------------------ idle timeout and ping

    @Test fun anActiveLinkIsDroppedAfterThreeIdleMinutesAndTheReasonIsRecorded() = runTest {
        val events = Recorder()
        val rig = Rig(this, linkEvents = events)
        val c = rig.activeGhost()
        rig.now += 179_000
        rig.engine.tick()
        assertThat(c.isDisconnected()).isFalse()
        rig.now += 2_000
        rig.engine.tick()
        assertThat(c.isDisconnected()).isTrue()
        assertThat(events.closed.last().second).startsWith("idle timeout")
        assertThat(events.closed.last().third).isTrue()
    }

    @Test fun pingEverySixtySecondsKeepsALinkAliveIndefinitely() = runTest {
        val rig = Rig(this)
        val c = rig.activeGhost()
        repeat(10) {
            rig.now += 60_000
            rig.engine.tick()
            assertThat(c.ok(c.request("ping"))).isTrue()
        }
        assertThat(c.isDisconnected()).isFalse()
        rig.now += 181_000
        rig.engine.tick()
        assertThat(c.isDisconnected()).isTrue()
    }

    @Test fun pingReportsUptimeLinkStateAndStatusTimestampWithoutPumpTraffic() = runTest {
        val rig = Rig(this)
        val c = rig.activeGhost()
        rig.now += 42_000
        val before = rig.requestsSeen.size
        val r = c.request("ping")
        assertThat(c.ok(r)).isTrue()
        val result = c.result(r)
        assertThat(result["uptimeSeconds"]!!.jsonPrimitive.content.toLong()).isAtLeast(42L)
        assertThat(result["pumpLink"]!!.jsonPrimitive.content).isNotEmpty()
        assertThat(result.containsKey("statusTimestamp")).isTrue()
        assertThat(rig.requestsSeen.size).isEqualTo(before + 1)
    }

    @Test fun pingWorksWithoutAnyBackendAndNeverSuspends() = runTest {
        val clock = { 1_800_000_000_000L }
        val d = OpDispatcher(backend = { null }, store = IdempotencyStore(MemoryTextFile(), clock), clock = clock, bridgeVersion = "t")
        val r = d.handle(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":3,"op":"ping"}""") as JsonObject)
        assertThat(r["ok"]!!.jsonPrimitive.content).isEqualTo("true")
        val result = r["result"] as JsonObject
        assertThat(result["pumpLink"]!!.jsonPrimitive.content).isEqualTo("notPaired")
        assertThat(result["statusTimestamp"]).isEqualTo(JsonNull)
    }

    @Test fun noBusyRejectionExistsForNewcomersAnyMore() = runTest {
        val rig = Rig(this)
        rig.activeGhost()
        val newcomer = rig.central("BB:11")
        newcomer.sendHandshake(HandshakeCodec.sessionStart("0123456789abcdef", ByteArray(16)))
        val reply = newcomer.takeHandshake()!!
        assertThat(reply["t"]!!.jsonPrimitive.content).isNotEqualTo("error")
    }
}
