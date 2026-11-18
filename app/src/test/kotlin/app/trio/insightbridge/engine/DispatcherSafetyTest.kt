package app.trio.insightbridge.engine

import app.trio.insightbridge.backend.BolusStarted
import app.trio.insightbridge.backend.PumpBackend
import app.trio.insightbridge.backend.SimulatedPump
import app.trio.insightbridge.protocol.ErrorCode
import app.trio.insightbridge.protocol.PumpOpException
import app.trio.insightbridge.protocol.RequestParser
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** Review findings 1, 2, 3, 6, 12 (and the bolus amount echo): journal-first dispatching, cancellation, budgets. */
@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherSafetyTest {

    private val u1 = "00000000-0000-4000-8000-000000000001"
    private val u2 = "00000000-0000-4000-8000-000000000002"
    private var now = 1_800_000_000_000L
    private val clock: () -> Long = { now }
    private val sim = SimulatedPump(clock)

    private fun frame(id: Int, op: String, args: String): JsonObject =
        RequestParser.parseObject("""{"id":$id,"op":"$op","args":$args}""".toByteArray())

    private val tbrArgs = """{"percent":120,"duration":30,"requestId":"$u1"}"""
    private val bolusArgs = """{"amount":1.0,"requestId":"$u1"}"""

    private fun ok(r: JsonObject) = r["ok"]!!.jsonPrimitive.content == "true"
    private fun code(r: JsonObject) = r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    private fun uncertain(r: JsonObject) = r["error"]!!.jsonObject["uncertain"]!!.jsonPrimitive.content == "true"

    // ---------------------------------------------------------------- 1: journal first

    @Test fun replayOfAnExecutedCommandNeverTouchesTheBackend() = runTest {
        val store = IdempotencyStore(MemoryTextFile(), clock)
        var backend: PumpBackend? = sim
        val d = OpDispatcher({ backend }, store, clock, "x")
        val first = d.handle(frame(1, "setTBR", tbrArgs))
        assertThat(ok(first)).isTrue()
        backend = null // pump gone / not paired: a replay must still return the stored success
        val replay = d.handle(frame(2, "setTBR", tbrArgs))
        assertThat(ok(replay)).isTrue()
        assertThat(replay["result"]).isEqualTo(first["result"])
    }

    @Test fun replayIsAnsweredEvenIfLimitsWouldFail() = runTest {
        val store = IdempotencyStore(MemoryTextFile(), clock)
        var failing = false
        val flaky = object : PumpBackend by sim {
            override suspend fun limits(): PumpLimits {
                if (failing) throw PumpOpException(ErrorCode.PUMP_UNREACHABLE, "link down")
                return sim.limits()
            }
        }
        val d = OpDispatcher({ flaky }, store, clock, "x")
        assertThat(ok(d.handle(frame(1, "deliverBolus", bolusArgs)))).isTrue()
        failing = true
        val replay = d.handle(frame(2, "deliverBolus", bolusArgs))
        assertThat(ok(replay)).isTrue() // before the fix: a certain pumpUnreachable for a bolus that WAS delivered
        assertThat(sim.cachedStatus()!!.boluses).hasSize(1)
    }

    @Test fun requestIsInProgressBeforeAnyBackendAccess() = runTest {
        val store = IdempotencyStore(MemoryTextFile(), clock)
        val gate = CompletableDeferred<Unit>()
        var stateWhileLimitsRead: String? = null
        val slow = object : PumpBackend by sim {
            override suspend fun limits(): PumpLimits {
                stateWhileLimitsRead = store.lookup(u1).state.wire
                gate.await()
                return sim.limits()
            }
        }
        val d = OpDispatcher({ slow }, store, clock, "x")
        var result: JsonObject? = null
        val job = launch { result = d.handle(frame(1, "deliverBolus", bolusArgs)) }
        runCurrent()
        assertThat(stateWhileLimitsRead).isEqualTo("inProgress")
        assertThat(store.lookup(u1).state.wire).isEqualTo("inProgress")
        // a second request with the same id while the first one is still working waits for the same outcome
        var second: JsonObject? = null
        val job2 = launch { second = d.handle(frame(2, "deliverBolus", bolusArgs)) }
        runCurrent()
        assertThat(second).isNull()
        gate.complete(Unit)
        advanceUntilIdle()
        job.join(); job2.join()
        assertThat(ok(result!!)).isTrue()
        assertThat(second!!["result"]).isEqualTo(result!!["result"])
        assertThat(sim.cachedStatus()!!.boluses).hasSize(1) // one bolus only
    }

    @Test fun rejectionIsRecordedAsTheCertainOutcomeAndReplayed() = runTest {
        val store = IdempotencyStore(MemoryTextFile(), clock)
        val d = OpDispatcher({ sim }, store, clock, "x")
        val bad = """{"amount":0.07,"requestId":"$u1"}"""
        val r1 = d.handle(frame(1, "deliverBolus", bad))
        assertThat(code(r1)).isEqualTo("invalidArgs")
        assertThat(store.lookup(u1).state.wire).isEqualTo("failed")
        val r2 = d.handle(frame(2, "deliverBolus", bad))
        assertThat(code(r2)).isEqualTo("invalidArgs")
        assertThat(r2["error"]).isEqualTo(r1["error"])
        assertThat(sim.cachedStatus()!!.boluses).isEmpty()
    }

    @Test fun notPairedIsRecordedWithoutExecuting() = runTest {
        val store = IdempotencyStore(MemoryTextFile(), clock)
        val d = OpDispatcher({ null }, store, clock, "x")
        assertThat(code(d.handle(frame(1, "setTBR", tbrArgs)))).isEqualTo("notPaired")
        assertThat(store.lookup(u1).state.wire).isEqualTo("failed")
    }

    // ---------------------------------------------------------------- 2: cancellation

    @Test fun cancellingAnInFlightCommandRecordsUncertainAndCompletesWaiters() = runTest {
        val store = IdempotencyStore(MemoryTextFile(), clock)
        val stuck = object : PumpBackend by sim {
            override suspend fun setTbr(percent: Int, duration: Int) { awaitCancellation() }
        }
        val d = OpDispatcher({ stuck }, store, clock, "x")
        val job = launch { d.handle(frame(1, "setTBR", tbrArgs)) }
        runCurrent()
        var waiter: JsonObject? = null
        val waitJob = launch { waiter = d.handle(frame(2, "setTBR", tbrArgs)) }
        runCurrent()
        assertThat(store.lookup(u1).state.wire).isEqualTo("inProgress")
        job.cancelAndJoin() // service stop / watchdog restart
        advanceUntilIdle()
        waitJob.join()
        val l = store.lookup(u1)
        assertThat(l.state.wire).isEqualTo("uncertain") // not in flight forever
        assertThat(waiter!!["error"]!!.jsonObject["uncertain"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(store.begin(u1, "setTBR", "percent=120;duration=30")).isInstanceOf(IdempotencyStore.Begin.Replay::class.java)
    }

    @Test fun cancellingBeforeTheCommandWasSentIsACertainFailure() = runTest {
        val store = IdempotencyStore(MemoryTextFile(), clock)
        val slowLimits = object : PumpBackend by sim {
            override suspend fun limits(): PumpLimits { awaitCancellation() }
        }
        val d = OpDispatcher({ slowLimits }, store, clock, "x")
        val job = launch { d.handle(frame(1, "deliverBolus", bolusArgs)) }
        runCurrent()
        job.cancelAndJoin()
        val l = store.lookup(u1)
        assertThat(l.state.wire).isEqualTo("failed")
        assertThat(l.outcome!!.uncertain).isFalse()
    }

    // ---------------------------------------------------------------- 3: persist failure after success

    private class FailAfter(private val ok: Int) : TextFile {
        var writes = 0
        var text: String? = null
        override fun read() = text
        override fun write(text: String) {
            if (++writes > ok) throw java.io.IOException("disk full")
            this.text = text
        }
    }

    @Test fun failingJournalWriteAfterASuccessfulDoseStillReportsTheRealOutcome() = runTest {
        val file = FailAfter(ok = 2) // begin() persists (1) and the init persist (2); the completion write fails
        val store = IdempotencyStore(file, clock)
        val d = OpDispatcher({ sim }, store, clock, "x")
        val r = d.handle(frame(1, "deliverBolus", bolusArgs))
        assertThat(ok(r)).isTrue() // real outcome, not an internal error
        assertThat(sim.cachedStatus()!!.boluses).hasSize(1)
        assertThat(store.lookup(u1).state.wire).isEqualTo("succeeded") // in memory, deferred completed
        val replay = d.handle(frame(2, "deliverBolus", bolusArgs))
        assertThat(ok(replay)).isTrue()
        assertThat(sim.cachedStatus()!!.boluses).hasSize(1) // never doses again
    }

    @Test fun inFlightWaitersAreCompletedEvenIfTheCompletionWriteFails() = runTest {
        val file = FailAfter(ok = 2)
        val store = IdempotencyStore(file, clock)
        assertThat(store.begin(u1, "setTBR", "x")).isEqualTo(IdempotencyStore.Begin.Proceed)
        val waiting = (store.begin(u1, "setTBR", "x") as IdempotencyStore.Begin.InFlight).deferred
        store.complete(u1, StoredOutcome(true, null, null, null, false))
        assertThat(waiting.isCompleted).isTrue()
    }

    // ---------------------------------------------------------------- 6: budgets and late replies

    @Test fun backendSeesTheDispatcherBudgetNotAFreshOne() = runTest {
        val store = IdempotencyStore(MemoryTextFile(), clock)
        var remainingInLimits = -1L
        var remainingInCommand = -1L
        val spy = object : PumpBackend by sim {
            override suspend fun limits(): PumpLimits { remainingInLimits = opRemainingMs(Long.MAX_VALUE); return sim.limits() }
            override suspend fun deliverBolus(amount: BigDecimal): BolusStarted {
                remainingInCommand = opRemainingMs(Long.MAX_VALUE); return sim.deliverBolus(amount)
            }
        }
        val d = OpDispatcher({ spy }, store, clock, "x", dosingTimeoutMillis = 40_000)
        d.handle(frame(1, "deliverBolus", bolusArgs))
        assertThat(remainingInLimits).isIn(com.google.common.collect.Range.openClosed(0L, 40_000L))
        assertThat(remainingInCommand).isIn(com.google.common.collect.Range.openClosed(0L, 40_000L))
    }

    @Test fun backendThatNeverAnswersEndsUncertainWithinTheBudget() = runTest {
        val store = IdempotencyStore(MemoryTextFile(), clock)
        val hang = object : PumpBackend by sim {
            override suspend fun setTbr(percent: Int, duration: Int) { delay(10_000_000) }
        }
        val d = OpDispatcher({ hang }, store, clock, "x", dosingTimeoutMillis = 40_000)
        val before = testScheduler.currentTime
        val r = d.handle(frame(1, "setTBR", tbrArgs))
        assertThat(uncertain(r)).isTrue()
        assertThat(testScheduler.currentTime - before).isAtMost(40_000L + OpDispatcher.TIMEOUT_BACKSTOP_MS)
    }

    @Test fun lateRealReplyAfterTheDispatcherGaveUpCorrectsTheJournal() = runTest {
        val store = IdempotencyStore(MemoryTextFile(), clock)
        var ctxOfCommand: OpContext? = null
        val timedOut = object : PumpBackend by sim {
            override suspend fun setTbr(percent: Int, duration: Int) {
                ctxOfCommand = currentOpContext()
                throw PumpOpException(ErrorCode.PUMP_UNREACHABLE, "no answer in time", uncertain = true)
            }
        }
        val d = OpDispatcher({ timedOut }, store, clock, "x")
        val r = d.handle(frame(1, "setTBR", tbrArgs))
        assertThat(uncertain(r)).isTrue()
        assertThat(store.lookup(u1).state.wire).isEqualTo("uncertain")
        // the pump answers late: success
        ctxOfCommand!!.late(Result.success(Unit))
        val l = store.lookup(u1)
        assertThat(l.state.wire).isEqualTo("succeeded")
        assertThat(l.outcome!!.result!!["percent"]!!.jsonPrimitive.content).isEqualTo("120")
        // a replay now returns the real outcome
        assertThat(ok(d.handle(frame(2, "setTBR", tbrArgs)))).isTrue()
    }

    @Test fun lateReplyNeverOverridesACertainOrSuccessfulOutcome() {
        val store = IdempotencyStore(MemoryTextFile(), clock)
        store.begin(u1, "setTBR", "x")
        store.complete(u1, StoredOutcome(true, null, null, null, false))
        assertThat(store.amend(u1, StoredOutcome(false, null, ErrorCode.INTERNAL, "x", true))).isFalse()
        store.begin(u2, "setTBR", "x")
        store.complete(u2, StoredOutcome(false, null, ErrorCode.PUMP_BUSY, "busy", false))
        assertThat(store.amend(u2, StoredOutcome(true, null, null, null, false))).isFalse()
        assertThat(store.lookup(u2).state.wire).isEqualTo("failed")
    }

    // ---------------------------------------------------------------- 12: setDateTime

    @Test fun setDateTimeTimeoutIsUncertain() = runTest {
        val hang = object : PumpBackend by sim {
            override suspend fun setDateTime(utc: java.time.Instant, tzOffsetMinutes: Int) { delay(10_000_000) }
        }
        val d = OpDispatcher({ hang }, IdempotencyStore(MemoryTextFile(), clock), clock, "x")
        val utc = java.time.Instant.ofEpochMilli(now).toString()
        val r = d.handle(frame(1, "setDateTime", """{"utc":"$utc","tzOffsetMinutes":60}"""))
        assertThat(code(r)).isEqualTo("pumpUnreachable")
        assertThat(uncertain(r)).isTrue()
    }

    @Test fun setDateTimeWaitsForARunningDosingCommand() = runTest {
        val gate = CompletableDeferred<Unit>()
        val order = ArrayList<String>()
        val backend = object : PumpBackend by sim {
            override suspend fun setTbr(percent: Int, duration: Int) { order.add("tbr-start"); gate.await(); order.add("tbr-end") }
            override suspend fun setDateTime(utc: java.time.Instant, tzOffsetMinutes: Int) { order.add("datetime") }
        }
        val d = OpDispatcher({ backend }, IdempotencyStore(MemoryTextFile(), clock), clock, "x")
        val utc = java.time.Instant.ofEpochMilli(now).toString()
        val j1: Job = launch { d.handle(frame(1, "setTBR", tbrArgs)) }
        runCurrent()
        val j2: Job = launch { d.handle(frame(2, "setDateTime", """{"utc":"$utc","tzOffsetMinutes":60}""")) }
        runCurrent()
        assertThat(order).containsExactly("tbr-start") // datetime is serialised behind the dosing command
        gate.complete(Unit)
        advanceUntilIdle()
        j1.join(); j2.join()
        assertThat(order).containsExactly("tbr-start", "tbr-end", "datetime").inOrder()
    }

    // ---------------------------------------------------------------- bolus amount echo

    @Test fun bolusResultEchoesTheAmountTheBackendReportsAsProgrammed() = runTest {
        val pumpSays = object : PumpBackend by sim {
            override suspend fun deliverBolus(amount: BigDecimal): BolusStarted = BolusStarted(7, BigDecimal("0.95"))
        }
        val d = OpDispatcher({ pumpSays }, IdempotencyStore(MemoryTextFile(), clock), clock, "x")
        val r = d.handle(frame(1, "deliverBolus", """{"amount":1.0,"requestId":"$u1"}"""))
        val res = r["result"]!!.jsonObject
        assertThat(res["bolusId"]!!.jsonPrimitive.content).isEqualTo("7")
        assertThat(res["amount"]!!.jsonPrimitive.content.toDouble()).isEqualTo(0.95)
    }
}
