/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Soak-style JVM test: >= 100k messages through fragmentation, AES-GCM envelopes, JSON, validation,
 * idempotency and the simulator, checking that the retained state does not grow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoakTest {

    private fun usedHeapMb(): Long {
        repeat(3) { System.gc(); Thread.sleep(30) }
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024)
    }

    @Test fun hundredThousandMessagesDoNotGrowRetainedState() = runTest(timeout = 10.minutes) {
        val rig = Rig(this)
        val c = rig.pairedSession()
        val total = 100_000
        var okCount = 0
        var heapEarly = 0L
        var retainedEarly = 0
        for (i in 1..total) {
            val response = when {
                i % 100 == 0 -> c.request("setTBR", """{"percent":${110 + (i / 100 % 10) * 10},"duration":15,"requestId":"${uuid(i)}"}""", id = i.toLong())
                i % 100 == 50 -> c.request("cancelTBR", """{"requestId":"${uuid(i)}"}""", id = i.toLong())
                i % 500 == 1 -> c.request("getStatus", id = i.toLong())
                else -> c.request("hello", """{"protocol":2,"app":"soak"}""", id = i.toLong())
            }
            if (c.ok(response)) okCount++
            c.events.clear(); rig.broadcast.clear()
            if (i % 1000 == 0) { rig.now += 1_000; rig.engine.tick() } // keeps the link alive and runs timers
            if (i == 20_000) { heapEarly = usedHeapMb(); retainedEarly = rig.engine.retainedStateSize() }
        }
        assertThat(okCount).isEqualTo(total)
        // retained engine state is bounded by design (ids remembered per link, one link)
        assertThat(rig.engine.retainedStateSize()).isAtMost(BridgeEngine.MAX_REMEMBERED_IDS + 5)
        assertThat(rig.engine.retainedStateSize()).isAtMost(retainedEarly + 5) // already saturated at 20k
        assertThat(rig.store.size).isAtMost(5000)
        assertThat(rig.link.toCentral.values.all { it.isEmpty() }).isTrue()
        assertThat(rig.link.disconnected).isEmpty()
        // simulator history is bounded too
        assertThat(kotlinx.coroutines.runBlocking { rig.sim.readHistory(null, 200) }.events.size).isAtMost(200)
        val heapLate = usedHeapMb()
        assertThat(heapLate - heapEarly).isLessThan(25)
    }

    @Test fun manyDosingRequestIdsKeepTheStoreAtItsCapacity() = runTest(timeout = 5.minutes) {
        val rig = Rig(this, maxStoreEntries = 64)
        val c = rig.pairedSession()
        for (i in 1..2_000) {
            c.request("setTBR", """{"percent":120,"duration":15,"requestId":"${uuid(i)}"}""", id = i.toLong())
        }
        assertThat(rig.store.size).isEqualTo(64)
        // the oldest id is forgotten (unknown), the newest still replays
        val old = c.result(c.request("getRequestOutcome", """{"requestId":"${uuid(1)}"}""", id = 5000))
        assertThat(old["state"].toString()).contains("unknown")
        val recent = c.result(c.request("getRequestOutcome", """{"requestId":"${uuid(2000)}"}""", id = 5001))
        assertThat(recent["state"].toString()).contains("succeeded")
    }

    @Test fun repeatedReconnectsDoNotLeakLinks() = runTest(timeout = 5.minutes) {
        val rig = Rig(this)
        val first = rig.pairedSession()
        rig.engine.onCentralDisconnected("AA:00")
        for (i in 1..2_000) {
            val c = rig.central("AA:00")
            c.ltk = first.ltk; c.clientId = first.clientId
            c.startSession()
            assertThat(c.ok(c.request("hello", """{"protocol":2}"""))).isTrue()
            rig.engine.onCentralDisconnected("AA:00")
        }
        assertThat(rig.engine.retainedStateSize()).isEqualTo(0)
        assertThat(rig.engine.phoneState.value).isEqualTo(PhoneLinkState.NONE)
    }

    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
}
