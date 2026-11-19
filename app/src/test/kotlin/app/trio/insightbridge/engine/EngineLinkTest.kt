package app.trio.insightbridge.engine

import app.trio.insightbridge.protocol.EcdhP256
import app.trio.insightbridge.protocol.HandshakeCodec
import app.trio.insightbridge.protocol.PairingCrypto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** Review finding 11: connection generations, idle timeout, newcomers vs. a handshake in progress. */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineLinkTest {

    private fun commit() = PairingCrypto.commitment(EcdhP256.generate().publicKey, ByteArray(16))

    @Test fun staleDisconnectOfAnOlderConnectionDoesNotKillTheReconnectedLink() = runTest {
        val rig = Rig(this)
        rig.engine.onCentralConnected("AA:00", generation = 1)
        rig.engine.onCentralConnected("AA:00", generation = 2) // same device reconnects quickly
        rig.engine.onCentralDisconnected("AA:00", generation = 1) // late callback of the first connection
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.HANDSHAKE) // link 2 still there
        // fragments tagged with the old generation are ignored, the new one works
        val c = FakeCentral("AA:00", rig)
        rig.pairing.open()
        val frags = app.trio.insightbridge.protocol.Fragmenter.fragment(
            app.trio.insightbridge.protocol.Envelope.plaintext(HandshakeCodec.pairStart(commit())), 0, 20
        )
        frags.forEach { rig.engine.onFragment("AA:00", it, generation = 1) }
        assertThat(c.takeHandshake()).isNull()
        frags.forEach { rig.engine.onFragment("AA:00", it, generation = 2) }
        assertThat(c.takeHandshake()!!["t"]!!.jsonPrimitive.content).isEqualTo("pairResp")
        rig.engine.onCentralDisconnected("AA:00", generation = 2)
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.NONE)
    }

    @Test fun engineDisconnectsTheRightGeneration() = runTest {
        val rig = Rig(this)
        rig.engine.onCentralConnected("AA:00", generation = 7)
        rig.now += 11_000
        rig.engine.tick()
        assertThat(rig.link.disconnectGenerations).contains("AA:00" to 7L)
    }

    @Test fun anActiveLinkWithoutAuthenticatedFramesIsDroppedAfterTheIdleTimeout() = runTest {
        val rig = Rig(this)
        val c = rig.pairedSession()
        c.request("hello", """{"protocol":2}""")
        rig.now += BridgeEngine.ACTIVE_IDLE_TIMEOUT_MS - 60_000
        rig.engine.tick()
        assertThat(c.isDisconnected()).isFalse()
        c.request("getStatus") // an authenticated frame resets the idle clock
        rig.now += BridgeEngine.ACTIVE_IDLE_TIMEOUT_MS - 60_000
        rig.engine.tick()
        assertThat(c.isDisconnected()).isFalse()
        rig.now += 61_000
        rig.engine.tick()
        assertThat(c.isDisconnected()).isTrue()
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.NONE)
    }

    @Test fun newcomerIsAcceptedWhileAPhoneIsMidPairingButCannotDisturbIt() = runTest {
        val rig = Rig(this)
        val phone = rig.central("AA:00")
        rig.pairing.open()
        phone.sendHandshake(HandshakeCodec.pairStart(commit()))
        assertThat(phone.takeHandshake()!!["t"]!!.jsonPrimitive.content).isEqualTo("pairResp")
        val other = rig.central("EE:66")
        other.sendHandshake(HandshakeCodec.sessionStart("0123456789abcdef", ByteArray(16)))
        // not paired yet: an ordinary reject, no "busy" and no disconnect
        assertThat(other.takeHandshake()!!["reason"]!!.jsonPrimitive.content).isEqualTo("unknownClient")
        assertThat(other.isDisconnected()).isFalse()
        assertThat(phone.isDisconnected()).isFalse()
    }

    @Test fun newcomerWithAPendingSessionDoesNotDisturbAnotherPendingSession() = runTest {
        val rig = Rig(this)
        val phone = rig.pairedSession() // session started, first encrypted frame not sent yet
        val newcomer = rig.central("CC:22")
        newcomer.ltk = phone.ltk; newcomer.clientId = phone.clientId
        newcomer.startSession() // handshake runs in parallel, accepted
        assertThat(phone.isDisconnected()).isFalse()
        assertThat(newcomer.isDisconnected()).isFalse()
        assertThat(phone.ok(phone.request("hello", """{"protocol":2}"""))).isTrue()
        // the older one authenticated first; the newcomer has not: it is still just a handshake
        assertThat(newcomer.isDisconnected()).isFalse()
    }

    @Test fun anIdleHandshakeLinkIsNoLongerReplacedByANewcomerButTimesOut() = runTest {
        val rig = Rig(this)
        val idle = rig.central("AA:00") // connected, never sent a handshake
        val fresh = rig.central("BB:11")
        assertThat(idle.isDisconnected()).isFalse()
        assertThat(fresh.isDisconnected()).isFalse()
        rig.now += 10_500
        rig.engine.tick()
        assertThat(idle.isDisconnected()).isTrue()
        assertThat(fresh.isDisconnected()).isTrue()
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.NONE)
    }
}
