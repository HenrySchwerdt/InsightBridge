/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ble

/** Advertising intensity: fast discovery while nobody is connected, light while a session is up (PROTOCOL.md 1a). */
enum class AdvMode { LOW_LATENCY, BALANCED }

/** Everything [AdvertisingPolicy] needs to know, gathered by [GattServerManager] once per supervision tick. */
data class AdvSnapshot(
    val now: Long,
    /** Bluetooth adapter on and runtime permissions granted. */
    val adapterReady: Boolean,
    val serverOpen: Boolean,
    /** `onServiceAdded` reported success for the current server. */
    val serviceAdded: Boolean,
    /** Milliseconds since the current server was opened (the service is added asynchronously). */
    val serverAgeMs: Long,
    /** Result of the periodic `server.getService(..) != null` check; null when not verified in this tick. */
    val serviceStillRegistered: Boolean?,
    /** `onStartSuccess` was received and nothing stopped advertising since. */
    val advertising: Boolean,
    val advertisedMode: AdvMode?,
    /** `startAdvertising` was called and neither callback answered yet. */
    val startPending: Boolean,
    val desiredMode: AdvMode,
    /** Consecutive failed start attempts (advertising or server). */
    val consecutiveFailures: Int,
    val lastAttemptAt: Long,
    /** The 60 s verification found a reason to refresh (a central is connected, or the last refresh is old). */
    val refreshDue: Boolean
)

sealed interface AdvAction {

    data object None : AdvAction
    /** Bluetooth is off or permissions are gone: release everything. */
    data object StopAll : AdvAction
    /** Tear the GATT server down and build it again (this also restarts advertising when the service is added). */
    data class RecreateServer(val reason: String) : AdvAction
    data class StartAdvertising(val mode: AdvMode) : AdvAction
    data class RestartAdvertising(val mode: AdvMode, val reason: String) : AdvAction
}

/**
 * Pure decision logic for keeping the GATT server and the advertisement alive without the user ever opening the
 * app. Unit tested; [GattServerManager] only gathers the snapshot and executes the returned action.
 */
object AdvertisingPolicy {

    const val INITIAL_BACKOFF_MS = 250L
    /** PROTOCOL.md 1a: retries never back off beyond 5 s. */
    const val MAX_BACKOFF_MS = 5_000L
    /** `addService` normally completes within a few hundred ms. */
    const val SERVICE_ADD_GRACE_MS = 5_000L
    /** A `startAdvertising` that was never answered counts as failed after this long. */
    const val START_PENDING_TIMEOUT_MS = 4_000L
    const val VERIFY_INTERVAL_MS = 60_000L
    /** Advertising is refreshed at least this often even when it looks fine (cannot be queried from the platform). */
    const val FORCED_REFRESH_MS = 10 * 60_000L

    fun backoffMs(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0
        val shift = (consecutiveFailures - 1).coerceAtMost(10)
        return (INITIAL_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    fun desiredMode(sessionActive: Boolean): AdvMode = if (sessionActive) AdvMode.BALANCED else AdvMode.LOW_LATENCY

    fun decide(s: AdvSnapshot): AdvAction {
        if (!s.adapterReady) return if (s.serverOpen) AdvAction.StopAll else AdvAction.None
        val retryAllowed = s.now - s.lastAttemptAt >= backoffMs(s.consecutiveFailures)
        if (!s.serverOpen) {
            return if (retryAllowed) AdvAction.RecreateServer("gatt server not open") else AdvAction.None
        }
        if (s.serviceStillRegistered == false) {
            return if (retryAllowed) AdvAction.RecreateServer("gatt service disappeared") else AdvAction.None
        }
        if (!s.serviceAdded) {
            return if (s.serverAgeMs > SERVICE_ADD_GRACE_MS && retryAllowed) AdvAction.RecreateServer("service was never added") else AdvAction.None
        }
        if (s.startPending) {
            // Waiting for the callback; a missing callback is a failure that the next tick retries.
            return if (s.now - s.lastAttemptAt > START_PENDING_TIMEOUT_MS) AdvAction.RestartAdvertising(s.desiredMode, "start callback never arrived") else AdvAction.None
        }
        if (!s.advertising) {
            return if (retryAllowed) AdvAction.StartAdvertising(s.desiredMode) else AdvAction.None
        }
        if (s.advertisedMode != s.desiredMode) return AdvAction.RestartAdvertising(s.desiredMode, "mode change to ${s.desiredMode}")
        if (s.refreshDue) return AdvAction.RestartAdvertising(s.desiredMode, "periodic refresh")
        return AdvAction.None
    }

    /** Whether the periodic (60 s) verification should refresh the advertisement even though it looks healthy. */
    fun refreshDue(connectedCount: Int, now: Long, lastStartAt: Long): Boolean =
        connectedCount > 0 || now - lastStartAt >= FORCED_REFRESH_MS
}

/** Human readable names for `BluetoothGatt` / HCI status codes seen in connection state changes (for the log). */
object GattStatus {

    fun describe(status: Int): String = when (status) {
        0 -> "success"
        8 -> "connection timeout (supervision)"
        19 -> "terminated by remote user"
        22 -> "terminated by local host"
        34 -> "LMP/LL response timeout"
        62 -> "connection failed to be established"
        133 -> "generic GATT error (133)"
        257 -> "GATT failure"
        else -> "status $status"
    }
}
