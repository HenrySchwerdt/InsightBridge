/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import app.trio.insightbridge.protocol.ErrorCode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IdempotencyStoreTest {

    private var now = 1_000_000L
    private val file = MemoryTextFile()
    private fun store() = IdempotencyStore(file, { now })
    private val ok = StoredOutcome(true, buildJsonObject { put("bolusId", 3) }, null, null, false)
    private fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    @Test fun firstUseProceedsAndReplayReturnsStoredOutcome() {
        val s = store()
        assertThat(s.begin(id(1), "deliverBolus", "a")).isEqualTo(IdempotencyStore.Begin.Proceed)
        s.complete(id(1), ok)
        val r = s.begin(id(1), "deliverBolus", "a") as IdempotencyStore.Begin.Replay
        assertThat(r.outcome).isEqualTo(ok)
    }

    @Test fun sameIdWithDifferentArgumentsOrOpIsMismatch() {
        val s = store()
        s.begin(id(1), "deliverBolus", "amount=1"); s.complete(id(1), ok)
        assertThat(s.begin(id(1), "deliverBolus", "amount=2")).isEqualTo(IdempotencyStore.Begin.Mismatch)
        assertThat(s.begin(id(1), "setTBR", "amount=1")).isEqualTo(IdempotencyStore.Begin.Mismatch)
    }

    @Test fun inFlightRequestIsSharedNotRepeated() {
        val s = store()
        s.begin(id(1), "deliverBolus", "a")
        assertThat(s.begin(id(1), "deliverBolus", "a")).isInstanceOf(IdempotencyStore.Begin.InFlight::class.java)
    }

    @Test fun outcomesSurviveRestart() {
        val s = store()
        s.begin(id(1), "deliverBolus", "a"); s.complete(id(1), ok)
        val again = store().begin(id(1), "deliverBolus", "a") as IdempotencyStore.Begin.Replay
        assertThat(again.outcome).isEqualTo(ok)
    }

    @Test fun inFlightAtRestartBecomesUncertainFailure() {
        val s = store()
        s.begin(id(1), "deliverBolus", "a") // process "dies" before complete()
        val r = store().begin(id(1), "deliverBolus", "a") as IdempotencyStore.Begin.Replay
        assertThat(r.outcome.ok).isFalse()
        assertThat(r.outcome.uncertain).isTrue()
        assertThat(r.outcome.errorCode).isEqualTo(ErrorCode.INTERNAL)
    }

    @Test fun keepsOnlyTheLast64() {
        val s = store()
        for (i in 1..100) { s.begin(id(i), "setTBR", "x"); s.complete(id(i), ok) }
        assertThat(s.size).isEqualTo(64)
        assertThat(s.begin(id(100), "setTBR", "x")).isInstanceOf(IdempotencyStore.Begin.Replay::class.java)
        assertThat(s.begin(id(1), "setTBR", "x")).isEqualTo(IdempotencyStore.Begin.Proceed) // evicted
    }

    @Test fun entriesExpireAfter24Hours() {
        val s = store()
        s.begin(id(1), "setTBR", "x"); s.complete(id(1), ok)
        now += 24L * 3600 * 1000 + 1
        assertThat(s.begin(id(1), "setTBR", "x")).isEqualTo(IdempotencyStore.Begin.Proceed)
    }

    @Test fun entryJustUnder24HoursIsStillReplayed() {
        val s = store()
        s.begin(id(1), "setTBR", "x"); s.complete(id(1), ok)
        now += 24L * 3600 * 1000 - 1
        assertThat(s.begin(id(1), "setTBR", "x")).isInstanceOf(IdempotencyStore.Begin.Replay::class.java)
    }

    @Test fun errorOutcomesRoundTripThroughPersistence() {
        val s = store()
        val err = StoredOutcome(false, null, ErrorCode.PUMP_UNREACHABLE, "lost", true)
        s.begin(id(1), "deliverBolus", "a"); s.complete(id(1), err)
        assertThat((store().begin(id(1), "deliverBolus", "a") as IdempotencyStore.Begin.Replay).outcome).isEqualTo(err)
    }

    @Test fun corruptFileStartsEmptyInsteadOfCrashing() {
        file.write("{not json")
        assertThat(store().size).isEqualTo(0)
    }

    @Test fun unwritableStoreMeansTheCommandIsNotStartedAndNotRecorded() {
        val failing = object : TextFile {
            var fail = false
            override fun read(): String? = null
            override fun write(text: String) { if (fail) throw java.io.IOException("disk full") }
        }
        val s = IdempotencyStore(failing, { now })
        failing.fail = true
        assertThrows<IllegalStateException> { s.begin(id(1), "deliverBolus", "a") }
        assertThat(s.size).isEqualTo(0)
        failing.fail = false
        assertThat(s.begin(id(1), "deliverBolus", "a")).isEqualTo(IdempotencyStore.Begin.Proceed)
    }

    @Suppress("unused") private val p = JsonPrimitive(1)
}
