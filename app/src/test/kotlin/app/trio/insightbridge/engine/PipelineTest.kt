/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import app.trio.insightbridge.protocol.Envelope
import app.trio.insightbridge.protocol.Fragmenter
import app.trio.insightbridge.protocol.SealingChannel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** Whole request pipeline: fragments -> envelope -> handshake/session -> validate -> idempotency -> simulator -> response/events. */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineTest {

    private val u1 = "00000000-0000-4000-8000-000000000001"
    private val u2 = "00000000-0000-4000-8000-000000000002"
    private val u3 = "00000000-0000-4000-8000-000000000003"

    // ------------------------------------------------------------------ pairing

    @Test fun pairingWithSameCodeOnBothSidesStoresTheKeyAndAnswersPairDone() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        rig.pairing.open()
        c.pair()
        val done = c.takeHandshake()!!
        assertThat(done["t"]!!.jsonPrimitive.content).isEqualTo("pairDone")
        assertThat(rig.identity.ltk()).isEqualTo(c.ltk)
        assertThat(rig.identity.clientId()).isEqualTo("0123456789abcdef")
        assertThat(rig.pairing.state.value).isEqualTo(PairingUiState.Done)
        // the code the user compares is the one both sides derived
        assertThat(c.sasSeen).matches("[0-9]{6}")
    }

    @Test fun bridgeShowsTheSameSasTheIphoneComputed() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        rig.pairing.open()
        c.pair(userConfirms = false)
        val st = rig.pairing.state.value as PairingUiState.ConfirmCode
        assertThat(st.code).isEqualTo(c.sasSeen)
        assertThat(st.phoneConfirmed).isTrue()
        assertThat(rig.identity.ltk()).isNull() // nothing stored before the user confirmed on Android
        assertThat(c.takeHandshake()).isNull()  // no pairDone yet
        rig.pairing.userConfirm()
        assertThat(c.takeHandshake()!!["t"]!!.jsonPrimitive.content).isEqualTo("pairDone")
    }

    @Test fun userConfirmingFirstThenPhoneAlsoCompletes() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        rig.pairing.open()
        c.pair(userConfirms = false)
        rig.pairing.userConfirm()
        assertThat(rig.identity.ltk()).isNotNull()
    }

    @Test fun pairStartWithoutWindowGetsNotInPairingMode() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        val kp = app.trio.insightbridge.protocol.EcdhP256.generate()
        c.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.pairStart(app.trio.insightbridge.protocol.PairingCrypto.commitment(kp.publicKey, ByteArray(16))))
        val r = c.takeHandshake()!!
        assertThat(r["t"]!!.jsonPrimitive.content).isEqualTo("pairReject")
        assertThat(r["reason"]!!.jsonPrimitive.content).isEqualTo("notInPairingMode")
    }

    @Test fun windowExpiresAfter120Seconds() = runTest {
        val rig = Rig(this)
        rig.pairing.open()
        rig.now += 119_000
        assertThat(rig.pairing.isWindowOpen()).isTrue()
        rig.now += 2_000
        assertThat(rig.pairing.isWindowOpen()).isFalse()
        val c = rig.central()
        val kp = app.trio.insightbridge.protocol.EcdhP256.generate()
        c.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.pairStart(app.trio.insightbridge.protocol.PairingCrypto.commitment(kp.publicKey, ByteArray(16))))
        assertThat(c.takeHandshake()!!["reason"]!!.jsonPrimitive.content).isEqualTo("notInPairingMode")
    }

    @Test fun wrongConfirmMacAbortsAndStoresNothing() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        rig.pairing.open()
        c.pair(confirmMacOverride = ByteArray(32), userConfirms = false)
        val r = c.takeHandshake()!!
        assertThat(r["reason"]!!.jsonPrimitive.content).isEqualTo("badMac")
        assertThat(c.isDisconnected()).isTrue()
        rig.pairing.userConfirm()
        assertThat(rig.identity.ltk()).isNull()
    }

    @Test fun userRejectOnAndroidSendsPairRejectAndStoresNothing() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        rig.pairing.open()
        c.pair(userConfirms = false)
        rig.pairing.userReject()
        val r = c.takeHandshake()!!
        assertThat(r["t"]!!.jsonPrimitive.content).isEqualTo("pairReject")
        assertThat(r["reason"]!!.jsonPrimitive.content).isEqualTo("userRejected")
        assertThat(rig.identity.ltk()).isNull()
        assertThat(rig.pairing.state.value).isInstanceOf(PairingUiState.Failed::class.java)
    }

    @Test fun pairingANewIphoneReplacesTheOldOne() = runTest {
        val rig = Rig(this)
        val old = rig.pairedSession(clientId = "1111111111111111")
        assertThat(old.ok(old.request("hello", """{"protocol":2}"""))).isTrue()
        rig.engine.onCentralDisconnected("AA:00")
        val fresh = rig.central("BB:11")
        rig.pairing.open()
        fresh.pair("2222222222222222")
        assertThat(rig.identity.clientId()).isEqualTo("2222222222222222")
        // the old phone's long term key no longer works
        rig.engine.onCentralDisconnected("BB:11")
        val oldAgain = rig.central("AA:00"); oldAgain.ltk = old.ltk; oldAgain.clientId = "1111111111111111"
        val reply = oldAgain.startSession()
        assertThat(reply["t"]!!.jsonPrimitive.content).isEqualTo("sessionReject")
        assertThat(reply["reason"]!!.jsonPrimitive.content).isEqualTo("unknownClient")
    }

    @Test fun revealThatDoesNotMatchTheCommitmentIsRejectedAndClosesTheWindow() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        rig.pairing.open()
        c.pair(tamperReveal = true)
        val r = c.takeHandshake()!!
        assertThat(r["t"]!!.jsonPrimitive.content).isEqualTo("pairReject")
        assertThat(r["reason"]!!.jsonPrimitive.content).isEqualTo("commitMismatch")
        assertThat(rig.identity.ltk()).isNull()
        assertThat(rig.pairing.isWindowOpen()).isFalse()
        assertThat(rig.pairing.state.value).isInstanceOf(PairingUiState.Failed::class.java)
        // a second attempt in the same window is refused: the user has to tap again
        val c2 = rig.central("BB:11")
        val kp = app.trio.insightbridge.protocol.EcdhP256.generate()
        c2.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.pairStart(app.trio.insightbridge.protocol.PairingCrypto.commitment(kp.publicKey, ByteArray(16))))
        assertThat(c2.takeHandshake()!!["reason"]!!.jsonPrimitive.content).isEqualTo("notInPairingMode")
    }

    @Test fun onlyOnePairingAttemptPerWindowEvenIfItNeverFinishes() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        rig.pairing.open()
        val kp = app.trio.insightbridge.protocol.EcdhP256.generate()
        val commit = app.trio.insightbridge.protocol.PairingCrypto.commitment(kp.publicKey, ByteArray(16))
        c.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.pairStart(commit))
        assertThat(c.takeHandshake()!!["t"]!!.jsonPrimitive.content).isEqualTo("pairResp")
        // while the first phone is mid-pairing a second central is told "busy" (and does not disturb it)
        val c2 = rig.central("BB:11")
        c2.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.pairStart(commit))
        assertThat(c2.takeHandshake()!!["code"]!!.jsonPrimitive.content).isEqualTo("busy")
        assertThat(c.isDisconnected()).isFalse()
        // after the first one is gone, a new attempt in the same window is still refused
        rig.engine.onCentralDisconnected("AA:00")
        val c3 = rig.central("CC:33")
        c3.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.pairStart(commit))
        assertThat(c3.takeHandshake()!!["reason"]!!.jsonPrimitive.content).isEqualTo("notInPairingMode")
    }

    @Test fun pairingWithProtocolVersion1IsRejected() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        rig.pairing.open()
        val kp = app.trio.insightbridge.protocol.EcdhP256.generate()
        c.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.pairStart(app.trio.insightbridge.protocol.PairingCrypto.commitment(kp.publicKey, ByteArray(16)), version = 1))
        assertThat(c.takeHandshake()!!["reason"]!!.jsonPrimitive.content).isEqualTo("unsupportedProtocol")
    }

    @Test fun sessionStartWithProtocolVersion1IsRejected() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        rig.engine.onCentralDisconnected("AA:00")
        val c2 = rig.central("AA:00")
        c2.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.sessionStart(c.clientId, ByteArray(16), version = 1))
        assertThat(c2.takeHandshake()!!["reason"]!!.jsonPrimitive.content).isEqualTo("unsupportedProtocol")
    }

    @Test fun commitmentMatchesTheSpecFormula() {
        val pub = ByteArray(65) { it.toByte() }; val nonce = ByteArray(16) { (it + 100).toByte() }
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest("InsightBridge commit v2".toByteArray() + pub + nonce)
        assertThat(app.trio.insightbridge.protocol.PairingCrypto.commitment(pub, nonce)).isEqualTo(expected)
    }

    @Test fun pairConfirmBeforeRevealIsRejected() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        rig.pairing.open()
        val kp = app.trio.insightbridge.protocol.EcdhP256.generate()
        c.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.pairStart(app.trio.insightbridge.protocol.PairingCrypto.commitment(kp.publicKey, ByteArray(16))))
        c.takeHandshake()
        c.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.pairConfirm(ByteArray(32)))
        assertThat(c.takeHandshake()!!["t"]!!.jsonPrimitive.content).isEqualTo("pairReject")
        assertThat(rig.identity.ltk()).isNull()
    }

    @Test fun unauthenticatedRejectsAreNeverSentOnceASessionExists() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        c.request("hello", """{"protocol":2}""")
        rig.pairing.open()
        c.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.pairStart(ByteArray(32)))
        c.drain()
        assertThat(c.envelopes.none { it[0].toInt() == Envelope.TYPE_PLAINTEXT }).isTrue()
        assertThat(c.isDisconnected()).isTrue()
    }

    // ------------------------------------------------------------------ session

    @Test fun sessionRequiresPairing() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        val reply = c.startSession()
        assertThat(reply["reason"]!!.jsonPrimitive.content).isEqualTo("unknownClient")
    }

    @Test fun helloOverEncryptedSession() = runTest {
        val rig = Rig(this, bridgeVersion = "9.9.9")
        val c = rig.pairedSession()
        val r = c.request("hello", """{"protocol":2,"app":"Trio 1.0"}""")
        assertThat(c.ok(r)).isTrue()
        assertThat(c.result(r)["bridgeVersion"]!!.jsonPrimitive.content).isEqualTo("9.9.9")
        assertThat(c.result(r)["pumpPaired"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(rig.phoneActivity).isGreaterThan(0)
    }

    @Test fun helloWithAnotherProtocolIsUnsupported() = runTest {
        val c = Rig(this).pairedSession()
        val r = c.request("hello", """{"protocol":1}""")
        assertThat(c.code(r)).isEqualTo("unsupportedProtocol")
    }

    @Test fun noValidEncryptedMessageWithin10SecondsDropsTheLink() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        rig.now += 9_000; rig.engine.tick()
        assertThat(c.isDisconnected()).isFalse()
        rig.now += 1_500; rig.engine.tick()
        assertThat(c.isDisconnected()).isTrue()
    }

    @Test fun connectionWithoutAnyHandshakeIsDroppedAfterTimeout() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        rig.now += 11_000; rig.engine.tick()
        assertThat(c.isDisconnected()).isTrue()
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.NONE)
    }

    @Test fun phoneStateFollowsTheLink() = runTest {
        val rig = Rig(this)
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.NONE)
        val c = rig.pairedSession()
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.HANDSHAKE)
        c.request("hello", """{"protocol":2}""")
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.SESSION)
    }

    // ------------------------------------------------------------------ authentication / safety

    @Test fun replayedEnvelopeDropsTheConnectionAndDoesNotReachThePump() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        val env = c.sealer!!.seal("""{"id":1,"op":"deliverBolus","args":{"amount":1.0,"requestId":"$u1"}}""".toByteArray())
        c.sendEnvelope(env); rig.idle()
        c.readEncrypted()
        val reservoirAfterFirst = rig.sim.reservoirUnits()
        c.sendEnvelope(env); rig.idle() // exact replay
        assertThat(c.isDisconnected()).isTrue()
        assertThat(rig.requestsSeen.count { it == "deliverBolus" }).isEqualTo(1)
        assertThat(rig.sim.reservoirUnits()).isAtMost(reservoirAfterFirst)
    }

    @Test fun tamperedTagDropsTheConnectionAndNothingExecutes() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        val env = c.sealer!!.seal("""{"id":1,"op":"deliverBolus","args":{"amount":1.0,"requestId":"$u1"}}""".toByteArray())
        env[env.size - 1] = (env[env.size - 1].toInt() xor 1).toByte()
        c.sendEnvelope(env); rig.idle()
        assertThat(c.isDisconnected()).isTrue()
        assertThat(rig.requestsSeen).isEmpty()
        assertThat(run { rig.sim.cachedStatus()!!.boluses }).isEmpty()
    }

    @Test fun envelopeSealedWithAnotherKeyIsNotExecuted() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        val attacker = SealingChannel(ByteArray(32) { 1 }, Envelope.DIR_IPHONE_TO_BRIDGE)
        c.sendEnvelope(attacker.seal("""{"id":1,"op":"deliverBolus","args":{"amount":5.0,"requestId":"$u1"}}""".toByteArray())); rig.idle()
        assertThat(c.isDisconnected()).isTrue()
        assertThat(rig.requestsSeen).isEmpty()
    }

    @Test fun encryptedMessageBeforeSessionStartIsRejected() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        c.sendEnvelope(SealingChannel(ByteArray(32), Envelope.DIR_IPHONE_TO_BRIDGE).seal("""{"id":1,"op":"getStatus"}""".toByteArray()))
        assertThat(c.isDisconnected()).isTrue()
    }

    @Test fun plaintextHandshakeInsideAnActiveSessionDropsTheLink() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        c.request("hello", """{"protocol":2}""")
        c.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.sessionStart(c.clientId, ByteArray(16)))
        assertThat(c.isDisconnected()).isTrue()
    }

    @Test fun secondCentralIsToldBusyWhileASessionIsActive() = runTest {
        val rig = Rig(this)
        val first = rig.pairedSession()
        first.request("hello", """{"protocol":2}""")
        val second = rig.central("CC:22")
        second.sendHandshake(app.trio.insightbridge.protocol.HandshakeCodec.sessionStart("0123456789abcdef", ByteArray(16)))
        val r = second.takeHandshake()!!
        assertThat(r["code"]!!.jsonPrimitive.content).isEqualTo("busy")
        assertThat(second.isDisconnected()).isTrue()
        assertThat(first.isDisconnected()).isFalse()
        assertThat(first.ok(first.request("getStatus"))).isTrue()
    }

    @Test fun newCentralReplacesALinkThatNeverFinishedAHandshake() = runTest {
        val rig = Rig(this)
        val stale = rig.central("AA:00")
        val fresh = rig.central("BB:11")
        assertThat(stale.isDisconnected()).isTrue()
        assertThat(fresh.isDisconnected()).isFalse()
    }

    @Test fun oversizedMessageIsDroppedWithTooLarge() = runTest {
        val rig = Rig(this)
        val c = rig.central()
        rig.link.maxFragment = 512
        val big = ByteArray(20_000) { 'a'.code.toByte() }
        // build fragments by hand: the Fragmenter itself refuses messages above 16 KiB
        var seq = 5
        val chunk = 509
        var off = 0
        var first = true
        while (off < big.size) {
            val n = minOf(chunk, big.size - off)
            val last = off + n >= big.size
            val flags = (if (first) 1 else 0) or (if (last) 2 else 0)
            rig.engine.onFragment("AA:00", byteArrayOf(flags.toByte(), 0, seq.toByte()) + big.copyOfRange(off, off + n))
            first = false; off += n
        }
        val reply = c.takeHandshake()!!
        assertThat(reply["code"]!!.jsonPrimitive.content).isEqualTo("tooLarge")
    }

    @Test fun requestsAreReassembledFromFragmentsAtMtu23() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        rig.link.maxFragment = 20
        // a long app string forces many fragments both ways
        val r = c.request("hello", """{"protocol":2,"app":"${"x".repeat(60)}"}""")
        assertThat(c.ok(r)).isTrue()
    }

    @Test fun duplicateRequestIdWithinASessionIsRejected() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        assertThat(c.ok(c.request("getStatus", id = 7))).isTrue()
        val again = c.request("getStatus", id = 7)
        assertThat(c.code(again)).isEqualTo("invalidArgs")
    }

    // ------------------------------------------------------------------ operations

    @Test fun getStatusReturnsAllFieldsFromTheSimulator() = runTest {
        val c = Rig(this).pairedSession()
        val s = c.result(c.request("getStatus"))
        assertThat(s["serial"]!!.jsonPrimitive.content).isEqualTo("SIMULATOR")
        assertThat(s["firmware"]!!.jsonPrimitive.content).isEqualTo("sim")
        assertThat(s.keys).hasSize(14)
    }

    @Test fun invalidArgumentsAreRejectedBeforeTheyReachThePump() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        val cases = listOf(
            "deliverBolus" to """{"amount":0.07,"requestId":"$u1"}""",
            "deliverBolus" to """{"amount":10.05,"requestId":"$u1"}""",
            "deliverBolus" to """{"amount":0,"requestId":"$u1"}""",
            "deliverBolus" to """{"amount":-1,"requestId":"$u1"}""",
            "setTBR" to """{"percent":100,"duration":30,"requestId":"$u1"}""",
            "setTBR" to """{"percent":255,"duration":30,"requestId":"$u1"}""",
            "setTBR" to """{"percent":120,"duration":20,"requestId":"$u1"}""",
            "setBasalProfile" to """{"segments":[{"duration":600,"rate":1.0}],"requestId":"$u1"}""",
            "setBasalProfile" to """{"segments":[{"duration":1440,"rate":9.0}],"requestId":"$u1"}"""
        )
        for ((op, args) in cases) {
            val r = c.request(op, args)
            assertThat(c.code(r)).isEqualTo("invalidArgs")
            assertThat(c.uncertain(r)).isFalse()
        }
        val s = rig.sim.cachedStatus()!!
        assertThat(s.boluses).isEmpty(); assertThat(s.tbr).isNull()
        assertThat(rig.sim.reservoirUnits()).isWithin(0.01).of(200.0)
        // a rejection is the (certain) outcome of its requestId; a new intent uses a new requestId
        val outcome = c.result(c.request("getRequestOutcome", """{"requestId":"$u1"}"""))
        assertThat(outcome["state"]!!.jsonPrimitive.content).isEqualTo("failed")
        assertThat(c.ok(c.request("deliverBolus", """{"amount":0.5,"requestId":"$u2"}"""))).isTrue()
    }

    @Test fun bolusRespondsWhenStartedAndProgressEventsFollow() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        val r = c.request("deliverBolus", """{"amount":4.0,"requestId":"$u1"}""")
        assertThat(c.ok(r)).isTrue()
        assertThat(c.result(r)["bolusId"]!!.jsonPrimitive.content).isEqualTo("1")
        assertThat(c.result(r)["amount"]!!.jsonPrimitive.content.toDouble()).isEqualTo(4.0)
        // not finished yet
        assertThat(c.result(c.request("getStatus"))["boluses"]!!.jsonArray).hasSize(1)
        rig.now += 60_000
        rig.sim.tick(); rig.idle()
        c.readEncrypted()
        val progress = c.events.filter { it["evt"]!!.jsonPrimitive.content == "bolusProgress" }
        assertThat(progress).isNotEmpty()
        assertThat(progress.last()["data"]!!.jsonObject["bolusId"]!!.jsonPrimitive.content).isEqualTo("1")
        rig.now += 120_000
        rig.sim.tick(); rig.idle(); c.readEncrypted()
        assertThat(c.events.last { it["evt"]!!.jsonPrimitive.content == "bolusProgress" }["data"]!!.jsonObject["remaining"]!!.jsonPrimitive.content.toDouble()).isEqualTo(0.0)
        val hist = c.result(c.request("readHistory", """{"sinceSequence":null,"max":50}"""))["events"]!!.jsonArray
        assertThat(hist.map { it.jsonObject["type"]!!.jsonPrimitive.content }).containsAtLeast("bolusProgrammed", "bolusDelivered")
    }

    @Test fun requestIdReplayReturnsStoredOutcomeWithoutTouchingThePump() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        val args = """{"amount":1.0,"requestId":"$u1"}"""
        val first = c.request("deliverBolus", args)
        rig.now += 200_000; rig.sim.tick()
        val reservoir = rig.sim.reservoirUnits()
        val again = c.request("deliverBolus", args)
        assertThat(c.ok(again)).isTrue()
        assertThat(c.result(again)).isEqualTo(c.result(first))
        rig.now += 1; // no extra bolus
        assertThat(rig.sim.cachedStatus()!!.boluses).isEmpty()
        val bolusEvents = rig.sim.readHistoryBlocking().filter { it.type == "bolusProgrammed" }
        assertThat(bolusEvents).hasSize(1)
        assertThat(reservoir).isWithin(0.1).of(rig.sim.reservoirUnits())
    }

    @Test fun reusingARequestIdForADifferentCommandIsRejected() = runTest {
        val c = Rig(this).pairedSession()
        assertThat(c.ok(c.request("setTBR", """{"percent":120,"duration":30,"requestId":"$u1"}"""))).isTrue()
        val r = c.request("setTBR", """{"percent":130,"duration":30,"requestId":"$u1"}""")
        assertThat(c.code(r)).isEqualTo("invalidArgs")
        val s = c.result(c.request("getStatus"))["tbr"]!!.jsonObject
        assertThat(s["percent"]!!.jsonPrimitive.content).isEqualTo("120")
    }

    @Test fun uncertainOutcomeIsReportedAndNeverRetriedOrRepeated() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        rig.sim.injectUncertain("deliverBolus", executed = true)
        val args = """{"amount":2.0,"requestId":"$u1"}"""
        val r = c.request("deliverBolus", args)
        assertThat(c.ok(r)).isFalse()
        assertThat(c.code(r)).isEqualTo("pumpUnreachable")
        assertThat(c.uncertain(r)).isTrue()
        // the bridge did not retry on its own
        assertThat(rig.sim.readHistoryBlocking().count { it.type == "bolusProgrammed" }).isEqualTo(1)
        // replaying the same requestId returns the stored uncertain outcome and does NOT dose again
        val replay = c.request("deliverBolus", args)
        assertThat(c.uncertain(replay)).isTrue()
        assertThat(rig.sim.readHistoryBlocking().count { it.type == "bolusProgrammed" }).isEqualTo(1)
    }

    @Test fun uncertainOutcomeWhereThePumpDidNotExecute() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        rig.sim.injectUncertain("setTBR", executed = false)
        val r = c.request("setTBR", """{"percent":150,"duration":30,"requestId":"$u2"}""")
        assertThat(c.uncertain(r)).isTrue()
        assertThat(rig.sim.cachedStatus()!!.tbr).isNull()
        // resolving: getStatus shows nothing was applied; a NEW intent (new requestId) can be sent
        assertThat(c.ok(c.request("setTBR", """{"percent":150,"duration":30,"requestId":"$u3"}"""))).isTrue()
    }

    @Test fun linkDropGivesCertainPumpUnreachable() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        rig.sim.injectLinkDrop(60_000)
        val r = c.request("setTBR", """{"percent":150,"duration":30,"requestId":"$u1"}""")
        assertThat(c.code(r)).isEqualTo("pumpUnreachable")
        assertThat(c.uncertain(r)).isFalse()
        val ev = c.events.map { it["evt"]!!.jsonPrimitive.content }
        assertThat(ev).contains("pumpLink")
    }

    @Test fun stoppedPumpFailsBolusAndTbrWithPumpSuspendedNotUncertain() = runTest {
        val c = Rig(this).pairedSession()
        assertThat(c.ok(c.request("setOperatingMode", """{"mode":"stopped","requestId":"$u1"}"""))).isTrue()
        val b = c.request("deliverBolus", """{"amount":1.0,"requestId":"$u2"}""")
        assertThat(c.code(b)).isEqualTo("pumpSuspended"); assertThat(c.uncertain(b)).isFalse()
        val t = c.request("setTBR", """{"percent":50,"duration":30,"requestId":"$u3"}""")
        assertThat(c.code(t)).isEqualTo("pumpSuspended")
    }

    @Test fun tbrSetReplaceAndCancel() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        assertThat(c.result(c.request("setTBR", """{"percent":150,"duration":60,"requestId":"$u1"}"""))["percent"]!!.jsonPrimitive.content).isEqualTo("150")
        assertThat(c.ok(c.request("setTBR", """{"percent":50,"duration":30,"requestId":"$u2"}"""))).isTrue()
        assertThat(c.result(c.request("getStatus"))["tbr"]!!.jsonObject["percent"]!!.jsonPrimitive.content).isEqualTo("50")
        assertThat(c.ok(c.request("cancelTBR", """{"requestId":"$u3"}"""))).isTrue()
        assertThat(c.result(c.request("getStatus"))["tbr"].toString()).isEqualTo("null")
    }

    @Test fun cancelBolusReturnsDeliveredAmount() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        val id = c.result(c.request("deliverBolus", """{"amount":5.0,"requestId":"$u1"}"""))["bolusId"]!!.jsonPrimitive.content
        rig.now += 60_000
        val r = c.request("cancelBolus", """{"bolusId":$id}""")
        assertThat(c.result(r)["delivered"]!!.jsonPrimitive.content.toDouble()).isWithin(0.02).of(2.0)
        val finished = c.request("cancelBolus", """{"bolusId":$id}""")
        assertThat(c.result(finished)["delivered"].toString()).isEqualTo("null")
    }

    @Test fun basalProfileIsWrittenAndActivated() = runTest {
        val c = Rig(this).pairedSession()
        val r = c.request("setBasalProfile", """{"segments":[{"duration":720,"rate":0.5},{"duration":720,"rate":1.25}],"requestId":"$u1"}""")
        assertThat(c.result(r)["activeProfile"]!!.jsonPrimitive.content).isEqualTo("1")
    }

    @Test fun historyPagingThroughTheProtocol() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        repeat(6) {
            c.request("setTBR", """{"percent":120,"duration":15,"requestId":"00000000-0000-4000-8000-0000000001%02d"}""".format(it))
        }
        val newest = c.result(c.request("readHistory", """{"sinceSequence":null,"max":4}"""))
        val ev = newest["events"]!!.jsonArray
        assertThat(ev).hasSize(4)
        val seqs = ev.map { it.jsonObject["seq"]!!.jsonPrimitive.content.toLong() }
        assertThat(seqs).isInOrder()
        val since = c.result(c.request("readHistory", """{"sinceSequence":${seqs[1]},"max":10}"""))
        assertThat(since["events"]!!.jsonArray.map { it.jsonObject["seq"]!!.jsonPrimitive.content.toLong() }).containsExactly(seqs[2], seqs[3]).inOrder()
        assertThat(since["lastSequence"]!!.jsonPrimitive.content.toLong()).isEqualTo(seqs[3])
        assertThat(since["more"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(c.code(c.request("readHistory", """{"sinceSequence":null,"max":201}"""))).isEqualTo("invalidArgs")
    }

    @Test fun alertsAreConfirmedOnlyOnRequestAndMustMatch() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        c.request("hello", """{"protocol":2}""") // events flow only on an authenticated session
        rig.sim.injectAlert("MAINTENANCE_24"); rig.idle()
        c.readEncrypted()
        val alertEvents = c.events.filter { it["evt"]!!.jsonPrimitive.content == "alert" }
        assertThat(alertEvents).hasSize(1)
        val a = alertEvents.single()["data"]!!.jsonObject
        assertThat(a["alertType"]!!.jsonPrimitive.content).isEqualTo("MAINTENANCE_24")
        assertThat(a["category"]!!.jsonPrimitive.content).isEqualTo("maintenance")
        // still active: the bridge never confirms by itself
        assertThat(c.result(c.request("getStatus"))["activeAlert"].toString()).isNotEqualTo("null")
        val id = a["alertId"]!!.jsonPrimitive.content
        assertThat(c.code(c.request("confirmAlert", """{"alertId":${id.toInt() + 1},"alertType":"MAINTENANCE_24"}"""))).isEqualTo("invalidArgs")
        assertThat(c.code(c.request("confirmAlert", """{"alertId":$id,"alertType":"NOT_A_TYPE"}"""))).isEqualTo("invalidArgs")
        assertThat(c.ok(c.request("snoozeAlert", """{"alertId":$id,"alertType":"MAINTENANCE_24"}"""))).isTrue()
        assertThat(c.ok(c.request("confirmAlert", """{"alertId":$id,"alertType":"MAINTENANCE_24"}"""))).isTrue()
        assertThat(c.result(c.request("getStatus"))["activeAlert"].toString()).isEqualTo("null")
    }

    @Test fun dateTimeAndPairingStatus() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        val utc = java.time.Instant.ofEpochMilli(rig.now).toString()
        assertThat(c.ok(c.request("setDateTime", """{"utc":"$utc","tzOffsetMinutes":60}"""))).isTrue()
        val bad = java.time.Instant.ofEpochMilli(rig.now - 3_600_000).toString()
        assertThat(c.code(c.request("setDateTime", """{"utc":"$bad","tzOffsetMinutes":60}"""))).isEqualTo("invalidArgs")
        val p = c.result(c.request("pumpPairingStatus"))
        assertThat(p["paired"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(p["serial"]!!.jsonPrimitive.content).isEqualTo("SIMULATOR")
    }

    @Test fun noBackendMeansNotPaired() = runTest {
        val rig = Rig(this)
        val dispatcher = OpDispatcher({ null }, IdempotencyStore(MemoryTextFile(), rig.clock), rig.clock, "x")
        val r = dispatcher.handle(app.trio.insightbridge.protocol.RequestParser.parseObject("""{"id":1,"op":"getStatus"}""".toByteArray()))
        assertThat(r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content).isEqualTo("notPaired")
        val d = dispatcher.handle(app.trio.insightbridge.protocol.RequestParser.parseObject("""{"id":2,"op":"deliverBolus","args":{"amount":1,"requestId":"$u1"}}""".toByteArray()))
        assertThat(d["error"]!!.jsonObject["code"]!!.jsonPrimitive.content).isEqualTo("notPaired")
    }

    @Test fun inFlightEntryFromACrashedProcessBecomesUncertainAfterRestart() = runTest {
        val rig = Rig(this)
        // simulate: command recorded, process died before the outcome
        rig.store.begin(u1, "deliverBolus", "amount=1")
        val restarted = IdempotencyStore(rig.idempotencyFile, rig.clock)
        val d = OpDispatcher({ rig.sim }, restarted, rig.clock, "x")
        val r = d.handle(app.trio.insightbridge.protocol.RequestParser.parseObject("""{"id":3,"op":"deliverBolus","args":{"amount":1,"requestId":"$u1"}}""".toByteArray()))
        val e = r["error"]!!.jsonObject
        assertThat(e["uncertain"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(rig.sim.readHistoryBlocking().none { it.type == "bolusProgrammed" }).isTrue()
    }

    @Test fun getRequestOutcomeReportsEveryState() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        fun outcome(id: String) = c.result(c.request("getRequestOutcome", """{"requestId":"$id"}"""))
        assertThat(outcome(u1)["state"]!!.jsonPrimitive.content).isEqualTo("unknown")
        assertThat(outcome(u1)["result"].toString()).isEqualTo("null")

        c.request("deliverBolus", """{"amount":1.0,"requestId":"$u1"}""")
        val ok = outcome(u1)
        assertThat(ok["state"]!!.jsonPrimitive.content).isEqualTo("succeeded")
        assertThat(ok["result"]!!.jsonObject["bolusId"]!!.jsonPrimitive.content).isEqualTo("1")
        assertThat(ok["error"].toString()).isEqualTo("null")
        assertThat(ok["completedAt"]!!.jsonPrimitive.content).endsWith("Z")

        rig.sim.injectFailure("setTBR", app.trio.insightbridge.protocol.ErrorCode.PUMP_BUSY)
        c.request("setTBR", """{"percent":120,"duration":30,"requestId":"$u2"}""")
        val failed = outcome(u2)
        assertThat(failed["state"]!!.jsonPrimitive.content).isEqualTo("failed")
        assertThat(failed["error"]!!.jsonObject["code"]!!.jsonPrimitive.content).isEqualTo("pumpBusy")
        assertThat(failed["result"].toString()).isEqualTo("null")

        rig.sim.injectUncertain("cancelTBR", executed = true)
        c.request("cancelTBR", """{"requestId":"$u3"}""")
        val unc = outcome(u3)
        assertThat(unc["state"]!!.jsonPrimitive.content).isEqualTo("uncertain")
        assertThat(unc["error"]!!.jsonObject["uncertain"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test fun requestIsRecordedInProgressBeforeAnyPumpTrafficThenFinal() = runTest {
        val rig = Rig(this)
        val seenDuringPumpCall = ArrayList<String>()
        val store = rig.store
        val slow = object : app.trio.insightbridge.backend.PumpBackend by rig.sim {
            override suspend fun setTbr(percent: Int, duration: Int) {
                // runs while the command is "talking to the pump"
                seenDuringPumpCall.add(store.lookup(u1).state.wire)
                rig.sim.setTbr(percent, duration)
            }
        }
        val d = OpDispatcher({ slow }, store, rig.clock, "x")
        val r = d.handle(app.trio.insightbridge.protocol.RequestParser.parseObject("""{"id":1,"op":"setTBR","args":{"percent":120,"duration":30,"requestId":"$u1"}}""".toByteArray()))
        assertThat(r["ok"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(seenDuringPumpCall).containsExactly("inProgress")
        assertThat(store.lookup(u1).state.wire).isEqualTo("succeeded")
    }

    @Test fun inProgressEntrySurvivingARestartIsReportedUncertain() = runTest {
        val rig = Rig(this)
        rig.store.begin(u1, "deliverBolus", "amount=1")
        assertThat(rig.store.lookup(u1).state.wire).isEqualTo("inProgress")
        val restarted = IdempotencyStore(rig.idempotencyFile, rig.clock)
        assertThat(restarted.lookup(u1).state.wire).isEqualTo("uncertain")
    }

    @Test fun phoneDisconnectLeavesTbrRunning() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        c.request("setTBR", """{"percent":150,"duration":60,"requestId":"$u1"}""")
        rig.engine.onCentralDisconnected("AA:00")
        assertThat(rig.sim.cachedStatus()!!.tbr).isNotNull()
        rig.now += 30 * 60_000
        assertThat(kotlinx.coroutines.runBlocking { rig.sim.getStatus() }.tbr!!.remaining).isEqualTo(30)
    }

    @Suppress("unused") private val unusedFragmenter = Fragmenter
    @Suppress("unused") private val unusedArr: JsonArray? = null
}

private fun app.trio.insightbridge.backend.SimulatedPump.readHistoryBlocking() =
    kotlinx.coroutines.runBlocking { readHistory(null, 200) }.events

private fun <T> run(block: () -> T): T = block()
