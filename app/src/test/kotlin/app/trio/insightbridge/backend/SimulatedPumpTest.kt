/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.backend

import app.trio.insightbridge.protocol.BasalSegment
import app.trio.insightbridge.protocol.ErrorCode
import app.trio.insightbridge.protocol.PumpOpException
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SimulatedPumpTest {

    private var now = 1_800_000_000_000L
    private val sim = SimulatedPump({ now }, bolusRateUnitsPerMinute = 2.0, initialReservoir = 100.0)

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }
    private fun minutes(n: Long) { now += n * 60_000 }

    @Test fun reportsSimulatorIdentity() {
        val s = run { sim.getStatus() }
        assertThat(s.serial).isEqualTo("SIMULATOR")
        assertThat(s.firmware).isEqualTo("sim")
        assertThat(s.operatingMode).isEqualTo("started")
        assertThat(sim.kind).isEqualTo(BackendKind.SIMULATED)
    }

    @Test fun bolusDeliversAtConfiguredRateAndHistoryHasProgrammedAndDelivered() {
        val id = run { sim.deliverBolus(BigDecimal("3.0")) }.bolusId
        assertThat(run { sim.getStatus() }.boluses!!.single().remaining).isEqualTo(3.0)
        now += 30_000 // 1 U at 2 U/min
        val mid = run { sim.getStatus() }.boluses!!.single()
        assertThat(mid.remaining).isWithin(0.011).of(2.0)
        now += 60_000
        val done = run { sim.getStatus() }
        assertThat(done.boluses).isEmpty()
        val types = run { sim.readHistory(null, 50) }.events.map { it.type }
        assertThat(types).containsAtLeast("bolusProgrammed", "bolusDelivered")
        assertThat(sim.reservoirUnits()).isWithin(0.05).of(100.0 - 3.0 - 0.8 * 0.025 /* basal for 1.5 min */)
        assertThat(id).isEqualTo(1)
    }

    @Test fun secondBolusWhileRunningIsPumpBusy() {
        run { sim.deliverBolus(BigDecimal("5.0")) }
        assertThat(assertThrows<PumpOpException> { run { sim.deliverBolus(BigDecimal("1.0")) } }.code).isEqualTo(ErrorCode.PUMP_BUSY)
    }

    @Test fun cancelBolusReturnsDeliveredAndStopsDelivery() {
        val id = run { sim.deliverBolus(BigDecimal("5.0")) }.bolusId
        now += 60_000
        val delivered = run { sim.cancelBolus(id) }
        assertThat(delivered).isWithin(0.011).of(2.0)
        now += 120_000
        assertThat(run { sim.getStatus() }.boluses).isEmpty()
        assertThat(run { sim.cancelBolus(id) }).isNull() // already finished
    }

    @Test fun tbrExpiresOverTimeAndWritesHistory() {
        run { sim.setTbr(150, 30) }
        minutes(10)
        val s = run { sim.getStatus() }.tbr!!
        assertThat(s.percent).isEqualTo(150); assertThat(s.remaining).isEqualTo(20); assertThat(s.duration).isEqualTo(30)
        minutes(21)
        assertThat(run { sim.getStatus() }.tbr).isNull()
        val ev = run { sim.readHistory(null, 50) }.events
        assertThat(ev.map { it.type }).containsAtLeast("tbrStart", "tbrEnd")
        assertThat(ev.first { it.type == "tbrEnd" }.data["duration"]!!.jsonPrimitive.content).isEqualTo("30")
    }

    @Test fun newTbrReplacesRunningOne() {
        run { sim.setTbr(150, 30) }; run { sim.setTbr(50, 60) }
        assertThat(run { sim.getStatus() }.tbr!!.percent).isEqualTo(50)
        run { sim.cancelTbr() }
        assertThat(run { sim.getStatus() }.tbr).isNull()
        run { sim.cancelTbr() } // cancelling nothing is fine
    }

    @Test fun reservoirDecreasesWithBasalAndTbrScalesIt() {
        val before = sim.reservoirUnits()
        minutes(60)
        val used = before - sim.reservoirUnits()
        assertThat(used).isWithin(0.01).of(0.80)
        run { sim.setTbr(200, 60) }
        val b2 = sim.reservoirUnits(); minutes(60)
        assertThat(b2 - sim.reservoirUnits()).isWithin(0.01).of(1.60)
    }

    @Test fun stoppedPumpRefusesBolusAndTbrWithPumpSuspended() {
        run { sim.setOperatingMode(false) }
        assertThat(assertThrows<PumpOpException> { run { sim.deliverBolus(BigDecimal("1")) } }.code).isEqualTo(ErrorCode.PUMP_SUSPENDED)
        assertThat(assertThrows<PumpOpException> { run { sim.setTbr(50, 30) } }.code).isEqualTo(ErrorCode.PUMP_SUSPENDED)
        run { sim.setOperatingMode(true) }
        run { sim.deliverBolus(BigDecimal("0.5")) }
    }

    @Test fun historySequenceStrictlyIncreasesAndPagingWorks() {
        repeat(5) { run { sim.setTbr(120, 15) }; run { sim.cancelTbr() } }
        val all = run { sim.readHistory(null, 200) }
        val seqs = all.events.map { it.seq }
        assertThat(seqs).isInOrder()
        assertThat(seqs.toSet()).hasSize(seqs.size)
        val newest3 = run { sim.readHistory(null, 3) }
        assertThat(newest3.events.map { it.seq }).containsExactlyElementsIn(seqs.takeLast(3)).inOrder()
        val page = run { sim.readHistory(seqs[2], 4) }
        assertThat(page.events.map { it.seq }).containsExactlyElementsIn(seqs.subList(3, 7)).inOrder()
        assertThat(page.more).isTrue()
        val last = run { sim.readHistory(seqs.last(), 10) }
        assertThat(last.events).isEmpty(); assertThat(last.more).isFalse(); assertThat(last.lastSequence).isEqualTo(seqs.last())
    }

    @Test fun linkDropFailsCommandsCertainlyThenRecovers() {
        sim.injectLinkDrop(10_000)
        val e = assertThrows<PumpOpException> { run { sim.setTbr(50, 30) } }
        assertThat(e.code).isEqualTo(ErrorCode.PUMP_UNREACHABLE); assertThat(e.uncertain).isFalse()
        assertThat(run { sim.cachedStatus() }!!.pumpLink).isEqualTo("disconnected")
        now += 10_001
        run { sim.setTbr(50, 30) }
    }

    @Test fun uncertainInjectionExecutedAndNotExecuted() {
        sim.injectUncertain("setTBR", executed = true)
        val e1 = assertThrows<PumpOpException> { run { sim.setTbr(50, 30) } }
        assertThat(e1.uncertain).isTrue()
        assertThat(run { sim.getStatus() }.tbr).isNotNull() // it really ran
        sim.injectUncertain(null, executed = false)
        val e2 = assertThrows<PumpOpException> { run { sim.cancelTbr() } }
        assertThat(e2.uncertain).isTrue()
        assertThat(run { sim.getStatus() }.tbr).isNotNull() // it did NOT run
    }

    @Test fun injectedFailureIsCertain() {
        sim.injectFailure("deliverBolus", ErrorCode.PUMP_BUSY)
        val e = assertThrows<PumpOpException> { run { sim.deliverBolus(BigDecimal("1")) } }
        assertThat(e.code).isEqualTo(ErrorCode.PUMP_BUSY); assertThat(e.uncertain).isFalse()
        assertThat(run { sim.getStatus() }.boluses).isEmpty()
    }

    @Test fun alertsCanBeInjectedConfirmedAndSnoozed() {
        sim.injectAlert("MAINTENANCE_24")
        val a = run { sim.getStatus() }.activeAlert!!
        assertThat(a.category).isEqualTo("maintenance"); assertThat(a.status).isEqualTo("active")
        assertThrows<PumpOpException> { run { sim.confirmAlert(a.alertId + 1, a.alertType) } }
        run { sim.snoozeAlert(a.alertId, a.alertType) }
        assertThat(run { sim.getStatus() }.activeAlert!!.status).isEqualTo("snoozed")
        run { sim.confirmAlert(a.alertId, a.alertType) }
        assertThat(run { sim.getStatus() }.activeAlert).isNull()
        sim.injectAlert("WARNING_31", remainingInsulin = 20.0)
        assertThat(run { sim.getStatus() }.activeAlert!!.remainingInsulin).isEqualTo(20.0)
    }

    @Test fun basalProfileIsUsedForTheScheduledRate() {
        run { sim.setBasalProfile(listOf(BasalSegment(720, BigDecimal("0.5")), BasalSegment(720, BigDecimal("1.5")))) }
        val rate = run { sim.getStatus() }.basal!!.rate
        assertThat(rate).isAnyOf(0.5, 1.5)
        run { sim.setTbr(200, 30) }
        assertThat(run { sim.getStatus() }.basal!!.rate).isEqualTo(rate) // TBR excluded
    }

    @Test fun limitsAreReportedWithSteps() {
        val l = run { sim.getStatus() }.limits!!
        assertThat(l.maxBolus).isEqualTo(10.0); assertThat(l.bolusStep).isEqualTo(0.05); assertThat(l.basalStep).isEqualTo(0.01)
    }
}
