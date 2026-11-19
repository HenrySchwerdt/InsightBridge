package app.trio.insightbridge.engine

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LinkStatsTest {

    private var now = 1_000_000_000_000L
    private val store = HashMap<String, String?>()
    private fun stats() = LinkStats({ now }, { store[it] }, { k, v -> store[k] = v })

    @Test fun countsSessionsOfTheLastTwentyFourHoursOnly() {
        val s = stats()
        s.sessionEstablished()
        now += 3_600_000
        s.sessionEstablished()
        assertThat(s.sessionsLast24h()).isEqualTo(2)
        now += 23 * 3_600_000L + 1_000 // the first one is now older than 24 h
        assertThat(s.sessionsLast24h()).isEqualTo(1)
        now += 2 * 3_600_000L
        assertThat(s.sessionsLast24h()).isEqualTo(0)
    }

    @Test fun remembersTheLastDisconnectReason() {
        val s = stats()
        assertThat(s.lastDisconnect()).isNull()
        s.linkClosed("idle timeout: no authenticated frame for 181 s", wasSession = true)
        now += 5_000
        s.linkClosed("GATT disconnect: connection timeout (supervision)", wasSession = true)
        assertThat(s.lastDisconnect()!!.reason).contains("supervision")
        assertThat(s.lastDisconnect()!!.wasSession).isTrue()
    }

    @Test fun survivesARestartThroughTheStore() {
        val a = stats()
        a.sessionEstablished()
        a.linkClosed("preempted by newer authenticated link 1|2", wasSession = true)
        val b = stats()
        assertThat(b.sessionsLast24h()).isEqualTo(1)
        assertThat(b.lastDisconnect()!!.reason).startsWith("preempted")
    }

    @Test fun garbageInTheStoreIsIgnored() {
        store[LinkStats.KEY_SESSIONS] = "x,,12a,"
        store[LinkStats.KEY_LAST] = "nonsense"
        val s = stats()
        assertThat(s.sessionsLast24h()).isEqualTo(0)
        assertThat(s.lastDisconnect()).isNull()
    }

    @Test fun neverGrowsWithoutBound() {
        val s = stats()
        repeat(LinkStats.MAX_SESSIONS + 50) { s.sessionEstablished() }
        assertThat(s.sessionsLast24h()).isEqualTo(LinkStats.MAX_SESSIONS)
    }
}
