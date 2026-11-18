/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Status and event DTOs, PROTOCOL.md sections 5.2 - 5.5. Null = unknown (all fields always present). */

@Serializable
data class AlertDto(
    val alertId: Int,
    val alertType: String,
    val category: String,
    val status: String,
    val tbrPercent: Int? = null,
    val remainingInsulin: Double? = null
)

@Serializable data class BatteryDto(val percent: Int?)

@Serializable data class ReservoirDto(val units: Double?, val cartridgeType: String?)

@Serializable data class BasalDto(val activeProfile: Int?, val rate: Double?)

@Serializable data class TbrDto(val percent: Int, val duration: Int, val remaining: Int, val start: String)

@Serializable data class ActiveBolusDto(val bolusId: Int, val type: String, val amount: Double, val remaining: Double)

@Serializable data class PumpTimeDto(val utc: String, val pumpLocal: String, val driftSeconds: Int)

@Serializable
data class LimitsDto(
    val maxBolus: Double?,
    val maxBasal: Double?,
    val bolusStep: Double,
    val basalStep: Double,
    val minBasal: Double?
)

@Serializable
data class StatusDto(
    val timestamp: String?,
    val pumpLink: String,
    val serial: String?,
    val firmware: String?,
    val operatingMode: String?,
    val battery: BatteryDto?,
    val reservoir: ReservoirDto?,
    val basal: BasalDto?,
    val tbr: TbrDto?,
    val boluses: List<ActiveBolusDto>?,
    val pumpTime: PumpTimeDto?,
    val limits: LimitsDto?,
    val activeAlert: AlertDto?,
    val lastHistorySequence: Long?
)

@Serializable
data class HistoryEventDto(
    val seq: Long,
    val time: String,
    val pumpLocal: String? = null,
    val type: String,
    val data: JsonObject
)

@Serializable data class BolusProgressDto(val bolusId: Int, val delivered: Double, val remaining: Double)

@Serializable data class PumpLinkDto(val state: String)

/** Pump link states as sent on the wire. */
enum class PumpLinkState(val wire: String) {
    CONNECTED("connected"),
    CONNECTING("connecting"),
    DISCONNECTED("disconnected"),
    NOT_PAIRED("notPaired")
}

data class HistoryPage(val events: List<HistoryEventDto>, val lastSequence: Long, val more: Boolean)

/** Operating modes on the wire. */
object WireMode {

    const val STARTED = "started"
    const val STOPPED = "stopped"
    const val PAUSED = "paused"
}
