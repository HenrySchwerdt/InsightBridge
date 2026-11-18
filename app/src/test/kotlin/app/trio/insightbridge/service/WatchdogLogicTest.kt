/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

import app.trio.insightbridge.service.WatchdogLogic.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WatchdogLogicTest {

    private val now = 10_000_000L
    private fun input(
        shouldRun: Boolean = true, running: Boolean = true, heartbeatAge: Long = 1_000, perms: Boolean = true, heartbeat: Long? = null
    ) = WatchdogLogic.Input(now, shouldRun, running, heartbeat ?: (now - heartbeatAge), perms)

    @Test fun nothingToDoBeforeSetupIsFinished() = assertThat(WatchdogLogic.decide(input(shouldRun = false, running = false))).isEqualTo(Decision.NOTHING)
    @Test fun healthyServiceIsLeftAlone() = assertThat(WatchdogLogic.decide(input())).isEqualTo(Decision.NOTHING)
    @Test fun notRunningServiceIsStarted() = assertThat(WatchdogLogic.decide(input(running = false))).isEqualTo(Decision.START_SERVICE)
    @Test fun missingPermissionsAreReportedInsteadOfStarting() =
        assertThat(WatchdogLogic.decide(input(running = false, perms = false))).isEqualTo(Decision.NOTIFY_PERMISSIONS_MISSING)

    @Test fun stalledHeartbeatRestartsTheService() {
        assertThat(WatchdogLogic.decide(input(heartbeatAge = WatchdogLogic.SOFT_STALL_MS + 1))).isEqualTo(Decision.RESTART_SERVICE)
        assertThat(WatchdogLogic.decide(input(heartbeatAge = WatchdogLogic.SOFT_STALL_MS))).isEqualTo(Decision.NOTHING)
    }

    @Test fun badlyStalledHeartbeatRestartsTheProcess() {
        assertThat(WatchdogLogic.decide(input(heartbeatAge = WatchdogLogic.HARD_STALL_MS + 1))).isEqualTo(Decision.RESTART_PROCESS)
    }

    @Test fun serviceThatNeverBeatYetIsNotKilled() = assertThat(WatchdogLogic.decide(input(heartbeat = 0))).isEqualTo(Decision.NOTHING)

    @Test fun outagePolicyNotifiesOncePerOutageAndClearsOnContact() {
        val p = PumpOutagePolicy(15 * 60_000L)
        val start = 0L
        assertThat(p.evaluate(14 * 60_000L, 0, start, true)).isEqualTo(PumpOutagePolicy.Action.NONE)
        assertThat(p.evaluate(15 * 60_000L + 1, 0, start, true)).isEqualTo(PumpOutagePolicy.Action.NOTIFY)
        assertThat(p.evaluate(20 * 60_000L, 0, start, true)).isEqualTo(PumpOutagePolicy.Action.NONE)
        assertThat(p.evaluate(21 * 60_000L, 20 * 60_000L, start, true)).isEqualTo(PumpOutagePolicy.Action.CLEAR)
        assertThat(p.evaluate(40 * 60_000L, 20 * 60_000L, start, true)).isEqualTo(PumpOutagePolicy.Action.NOTIFY)
    }

    @Test fun outageClockStartsAtServiceStartNotAtEpoch() {
        val p = PumpOutagePolicy()
        val bootAt = 1_000_000_000L
        assertThat(p.evaluate(bootAt + 60_000, 0, bootAt, true)).isEqualTo(PumpOutagePolicy.Action.NONE)
    }

    @Test fun noOutageNotificationWithoutPump() {
        val p = PumpOutagePolicy()
        assertThat(p.evaluate(10_000_000, 0, 0, false)).isEqualTo(PumpOutagePolicy.Action.NONE)
    }
}
