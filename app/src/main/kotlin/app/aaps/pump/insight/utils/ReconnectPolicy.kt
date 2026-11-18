/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.aaps.pump.insight.utils

import kotlin.random.Random

/**
 * Capped exponential backoff for pump reconnects. Never gives up: [nextDelayMillis] keeps returning
 * the capped delay for as long as it is asked. Pure logic so it can be unit tested.
 *
 * - The first failure after a link that had been working is retried at once (the pump drops the
 *   link now and then and a fresh attempt normally succeeds).
 * - After that the delay is initial, 2x, 4x ... up to [capMillis], with +-[jitter] randomisation so
 *   a phone and a pump do not lock-step.
 * - [onHealthy] (called when real data was exchanged) resets the series.
 */
class ReconnectPolicy(
    private val initialMillis: Long = 1_000,
    private val capMillis: Long = 60_000,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default
) {

    private var failures = 0
    private var hadHealthyLink = false

    val consecutiveFailures: Int get() = failures

    fun onHealthy() {
        failures = 0
        hadHealthyLink = true
    }

    fun reset() {
        failures = 0
        hadHealthyLink = false
    }

    fun nextDelayMillis(): Long {
        failures++
        if (failures == 1 && hadHealthyLink) return 0
        val exponent = (failures - 1 - if (hadHealthyLink) 1 else 0).coerceIn(0, 30)
        val base = minOf(capMillis, initialMillis shl exponent)
        if (jitter <= 0.0) return base
        val spread = base * jitter
        val jittered = base + (random.nextDouble() * 2 - 1) * spread
        return jittered.toLong().coerceIn(0L, capMillis)
    }
}
