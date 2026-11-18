/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

/** Pure decision logic of the 15 minute WorkManager watchdog (unit tested). */
object WatchdogLogic {

    const val SOFT_STALL_MS = 3 * 60_000L
    const val HARD_STALL_MS = 10 * 60_000L

    data class Input(
        val now: Long,
        /** The bridge has something to do (pump paired, iPhone paired or simulator enabled). */
        val shouldRun: Boolean,
        val serviceRunning: Boolean,
        /** Epoch ms of the service heartbeat (0 = none). */
        val lastHeartbeat: Long,
        val bluetoothPermissionsGranted: Boolean
    )

    enum class Decision {
        NOTHING,
        START_SERVICE,
        RESTART_SERVICE,
        RESTART_PROCESS,
        NOTIFY_PERMISSIONS_MISSING
    }

    fun decide(i: Input): Decision {
        if (!i.shouldRun) return Decision.NOTHING
        if (!i.bluetoothPermissionsGranted) return Decision.NOTIFY_PERMISSIONS_MISSING
        if (!i.serviceRunning) return Decision.START_SERVICE
        val age = i.now - i.lastHeartbeat
        if (i.lastHeartbeat > 0 && age > HARD_STALL_MS) return Decision.RESTART_PROCESS
        if (i.lastHeartbeat > 0 && age > SOFT_STALL_MS) return Decision.RESTART_SERVICE
        return Decision.NOTHING
    }
}

/**
 * Decides when to raise / clear the "pump not reachable for more than 15 minutes" notification.
 * One notification per outage.
 */
class PumpOutagePolicy(private val thresholdMs: Long = 15 * 60_000L) {

    enum class Action { NONE, NOTIFY, CLEAR }

    private var notified = false

    /**
     * [lastContact] epoch ms of the last pump exchange (0 = never since boot), [referenceStart] the
     * time monitoring started (service start / pairing); the outage clock starts at the later one.
     */
    fun evaluate(now: Long, lastContact: Long, referenceStart: Long, pumpPaired: Boolean): Action {
        if (!pumpPaired) {
            return if (notified) { notified = false; Action.CLEAR } else Action.NONE
        }
        val since = maxOf(lastContact, referenceStart)
        val outage = now - since > thresholdMs
        return when {
            outage && !notified -> { notified = true; Action.NOTIFY }
            !outage && notified -> { notified = false; Action.CLEAR }
            else -> Action.NONE
        }
    }
}
