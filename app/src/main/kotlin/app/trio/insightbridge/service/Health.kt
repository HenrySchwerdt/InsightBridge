/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

import app.trio.insightbridge.engine.PhoneLinkState
import app.trio.insightbridge.protocol.PumpLinkState

/** Everything the main screen and the persistent notification show about bridge health. */
data class HealthSnapshot(
    val now: Long = 0,
    val serviceStartedAt: Long = 0,
    val serviceRunning: Boolean = false,
    val bluetoothOn: Boolean = true,
    val serverOpen: Boolean = false,
    val advertising: Boolean = false,
    val pumpLink: PumpLinkState = PumpLinkState.NOT_PAIRED,
    val lastPumpContact: Long = 0,
    val phoneLink: PhoneLinkState = PhoneLinkState.NONE,
    val lastPhoneRequest: Long = 0,
    val simulated: Boolean = false,
    val simulatorConflict: Boolean = false,
    val pumpPaired: Boolean = false,
    val phonePaired: Boolean = false,
    val permissionsOk: Boolean = true,
    val notificationsOk: Boolean = true,
    val batteryExempt: Boolean = true,
    /** iPhone sessions established in the last 24 h (connection health). */
    val sessions24h: Int = 0,
    val lastDisconnectAt: Long = 0,
    val lastDisconnectReason: String? = null,
    val gattConnections: Int = 0,
    val advertiseError: Int = 0
) {

    val uptimeMs: Long get() = if (serviceStartedAt == 0L) 0 else (now - serviceStartedAt).coerceAtLeast(0)

    /** Red-warning conditions shown on the main screen. */
    enum class Warning { BATTERY_OPTIMIZATION, NOTIFICATIONS_OFF, BLUETOOTH_OFF, PERMISSIONS_MISSING, SIMULATOR_CONFLICT, NOT_ADVERTISING }

    val warnings: List<Warning>
        get() = buildList {
            if (!permissionsOk) add(Warning.PERMISSIONS_MISSING)
            if (!bluetoothOn) add(Warning.BLUETOOTH_OFF)
            if (!batteryExempt) add(Warning.BATTERY_OPTIMIZATION)
            if (!notificationsOk) add(Warning.NOTIFICATIONS_OFF)
            if (simulatorConflict) add(Warning.SIMULATOR_CONFLICT)
            if (serviceRunning && bluetoothOn && permissionsOk && phonePaired && !advertising) add(Warning.NOT_ADVERTISING)
        }
}
