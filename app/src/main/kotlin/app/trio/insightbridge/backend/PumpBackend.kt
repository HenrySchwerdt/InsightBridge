/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.backend

import app.aaps.pump.insight.descriptors.AlertType
import app.trio.insightbridge.engine.PumpLimits
import app.trio.insightbridge.protocol.AlertDto
import app.trio.insightbridge.protocol.BasalSegment
import app.trio.insightbridge.protocol.BolusProgressDto
import app.trio.insightbridge.protocol.HistoryPage
import app.trio.insightbridge.protocol.PumpLinkState
import app.trio.insightbridge.protocol.StatusDto
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Result of an accepted bolus: the id and the programmed amount as reported by the pump. */
data class BolusStarted(val bolusId: Int, val amount: BigDecimal)

enum class BackendKind { SIGHT, SIMULATED, NONE }

/** Unsolicited things a backend reports; the engine turns them into protocol events. */
sealed interface BackendEvent {

    data class StatusChanged(val status: StatusDto) : BackendEvent
    data class AlertChanged(val alert: AlertDto) : BackendEvent
    data class BolusProgress(val progress: BolusProgressDto) : BackendEvent
    data class LinkChanged(val state: PumpLinkState) : BackendEvent
}

/**
 * Everything the bridge asks of a pump. Implementations throw
 * [app.trio.insightbridge.protocol.PumpOpException] for every failure, and must set `uncertain`
 * only when a command may have reached the pump but its outcome is unknown.
 *
 * Implementations: [app.trio.insightbridge.backend.sight.SightPumpBackend] (real Accu-Chek Insight
 * over Bluetooth Classic) and [SimulatedPump] (developer simulator, no insulin involved).
 */
interface PumpBackend {

    val kind: BackendKind
    val events: SharedFlow<BackendEvent>
    val linkState: StateFlow<PumpLinkState>

    /** Epoch ms of the last successful exchange with the pump (0 = never). */
    val lastContact: StateFlow<Long>
    val isPaired: Boolean
    val serial: String?

    /** Live pump limits (reads them from the pump if not cached). */
    suspend fun limits(): PumpLimits

    /** Refreshes from the pump if reachable; falls back to the last known status. */
    suspend fun getStatus(): StatusDto

    fun cachedStatus(): StatusDto?

    /** Starts a bolus; returns as soon as the pump accepted it. The returned amount is what the pump reports as programmed. */
    suspend fun deliverBolus(amount: BigDecimal): BolusStarted
    suspend fun cancelBolus(bolusId: Int): Double?
    suspend fun setTbr(percent: Int, duration: Int)
    suspend fun cancelTbr()
    suspend fun setOperatingMode(started: Boolean)
    suspend fun setBasalProfile(segments: List<BasalSegment>): Int
    suspend fun setDateTime(utc: Instant, tzOffsetMinutes: Int)
    suspend fun readHistory(sinceSequence: Long?, max: Int): HistoryPage
    suspend fun confirmAlert(alertId: Int, alertType: String)
    suspend fun snoozeAlert(alertId: Int, alertType: String)

    fun close()
}

/** Alert names and categories as sent on the wire (AndroidAPS AlertType enum names, verbatim). */
object AlertCatalog {

    val knownTypes: Set<String> = AlertType.entries.map { it.name }.toSet()

    fun categoryOf(name: String): String = when {
        name.startsWith("REMINDER") -> "reminder"
        name.startsWith("MAINTENANCE") -> "maintenance"
        name.startsWith("WARNING") -> "warning"
        else -> "error"
    }
}
