/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.aaps.pump.insight.utils

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.jupiter.api.Test

class ReconnectPolicyTest {

    private fun noJitter(initial: Long = 1000, cap: Long = 60_000) = ReconnectPolicy(initial, cap, jitter = 0.0)

    @Test fun backoffDoublesFromInitialUpToTheCap() {
        val p = noJitter()
        val delays = List(10) { p.nextDelayMillis() }
        assertThat(delays).containsExactly(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L, 60000L, 60000L).inOrder()
    }

    @Test fun neverGivesUpAndStaysCapped() {
        val p = noJitter()
        repeat(100_000) { p.nextDelayMillis() }
        assertThat(p.nextDelayMillis()).isEqualTo(60_000L)
        assertThat(p.consecutiveFailures).isEqualTo(100_001)
    }

    @Test fun firstFailureAfterHealthyLinkRetriesImmediatelyThenBacksOff() {
        val p = noJitter()
        repeat(5) { p.nextDelayMillis() }
        p.onHealthy()
        assertThat(p.consecutiveFailures).isEqualTo(0)
        assertThat(p.nextDelayMillis()).isEqualTo(0L)
        assertThat(p.nextDelayMillis()).isEqualTo(1000L)
        assertThat(p.nextDelayMillis()).isEqualTo(2000L)
    }

    @Test fun resetForgetsHealthHistory() {
        val p = noJitter()
        p.onHealthy(); p.nextDelayMillis(); p.reset()
        assertThat(p.nextDelayMillis()).isEqualTo(1000L)
    }

    @Test fun jitterStaysWithinBoundsAndNeverExceedsCap() {
        val p = ReconnectPolicy(1000, 60_000, jitter = 0.2, random = Random(1))
        repeat(2000) {
            val d = p.nextDelayMillis()
            assertThat(d).isAtMost(60_000L)
            assertThat(d).isAtLeast(0L)
        }
        val q = ReconnectPolicy(1000, 60_000, jitter = 0.2, random = Random(2))
        assertThat(q.nextDelayMillis()).isIn(com.google.common.collect.Range.closed(800L, 1200L))
    }

    @Test fun smallCapIsRespected() {
        val p = noJitter(initial = 500, cap = 1500)
        assertThat(List(5) { p.nextDelayMillis() }).containsExactly(500L, 1000L, 1500L, 1500L, 1500L).inOrder()
    }
}
