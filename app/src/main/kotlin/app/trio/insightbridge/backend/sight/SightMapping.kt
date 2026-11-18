/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.backend.sight

import app.aaps.pump.insight.app_layer.history.history_events.BasalDeliveryChangedEvent
import app.aaps.pump.insight.app_layer.history.history_events.BolusDeliveredEvent
import app.aaps.pump.insight.app_layer.history.history_events.BolusProgrammedEvent
import app.aaps.pump.insight.app_layer.history.history_events.CannulaFilledEvent
import app.aaps.pump.insight.app_layer.history.history_events.CartridgeInsertedEvent
import app.aaps.pump.insight.app_layer.history.history_events.CartridgeRemovedEvent
import app.aaps.pump.insight.app_layer.history.history_events.DateTimeChangedEvent
import app.aaps.pump.insight.app_layer.history.history_events.EndOfTBREvent
import app.aaps.pump.insight.app_layer.history.history_events.HistoryEvent
import app.aaps.pump.insight.app_layer.history.history_events.OccurrenceOfAlertEvent
import app.aaps.pump.insight.app_layer.history.history_events.OccurrenceOfErrorEvent
import app.aaps.pump.insight.app_layer.history.history_events.OccurrenceOfMaintenanceEvent
import app.aaps.pump.insight.app_layer.history.history_events.OccurrenceOfWarningEvent
import app.aaps.pump.insight.app_layer.history.history_events.OperatingModeChangedEvent
import app.aaps.pump.insight.app_layer.history.history_events.StartOfTBREvent
import app.aaps.pump.insight.app_layer.history.history_events.TotalDailyDoseEvent
import app.aaps.pump.insight.app_layer.history.history_events.TubeFilledEvent
import app.aaps.pump.insight.descriptors.Alert
import app.aaps.pump.insight.descriptors.AlertCategory
import app.aaps.pump.insight.descriptors.AlertStatus
import app.aaps.pump.insight.descriptors.AlertType
import app.aaps.pump.insight.descriptors.BolusType
import app.aaps.pump.insight.descriptors.OperatingMode
import app.aaps.pump.insight.exceptions.ConnectionFailedException
import app.aaps.pump.insight.exceptions.DisconnectedException
import app.aaps.pump.insight.exceptions.app_layer_errors.AppLayerErrorException
import app.aaps.pump.insight.exceptions.app_layer_errors.BolusAmountNotInRangeException
import app.aaps.pump.insight.exceptions.app_layer_errors.MaximumNumberOfBolusTypeAlreadyRunningException
import app.aaps.pump.insight.exceptions.app_layer_errors.PumpBusyException
import app.aaps.pump.insight.exceptions.app_layer_errors.PumpStoppedException
import app.trio.insightbridge.protocol.AlertDto
import app.trio.insightbridge.protocol.ErrorCode
import app.trio.insightbridge.protocol.HistoryEventDto
import app.trio.insightbridge.protocol.PumpOpException
import app.trio.insightbridge.protocol.WireMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pure mapping between AndroidAPS-derived Sight types and the bridge wire model. */
object SightMapping {

    fun modeWire(m: OperatingMode?): String? = when (m) {
        OperatingMode.STARTED -> WireMode.STARTED
        OperatingMode.STOPPED -> WireMode.STOPPED
        OperatingMode.PAUSED -> WireMode.PAUSED
        null -> null
    }

    fun bolusTypeWire(t: BolusType?): String = when (t) {
        BolusType.EXTENDED -> "extended"
        BolusType.MULTIWAVE -> "multiwave"
        else -> "standard"
    }

    /**
     * TBR as seen NOW from a snapshot read at [readAtMs]: the remaining time shrinks with the elapsed time
     * (the TBR itself is deterministic), the start stays fixed, and an expired TBR is gone (null).
     */
    fun tbrView(tbr: app.aaps.pump.insight.descriptors.ActiveTBR, readAtMs: Long, nowMs: Long): app.trio.insightbridge.protocol.TbrDto? {
        val elapsedMs = (nowMs - readAtMs).coerceAtLeast(0)
        val remainingMin = tbr.remainingDuration - (elapsedMs / 60_000).toInt()
        if (remainingMin <= 0) return null
        val ranMin = (tbr.initialDuration - tbr.remainingDuration).coerceAtLeast(0)
        val start = Instant.ofEpochMilli(readAtMs).minus(ranMin.toLong(), ChronoUnit.MINUTES)
        return app.trio.insightbridge.protocol.TbrDto(tbr.percentage, tbr.initialDuration, remainingMin, iso(start))
    }

    enum class ProfileVerdict { NOT_SENT, SUCCESS, VERIFY_FAILED, UNCERTAIN }

    /**
     * Outcome of a basal profile write session. If nothing was transmitted the pump is untouched. Otherwise ONLY
     * the read-back counts, even when the session itself reported an error (the close message is still sent
     * after a refused write, so part or all of the profile may have been applied):
     * read-back unavailable -> uncertain; equal to the request -> success; different -> verifyFailed.
     */
    fun profileVerdict(sessionFailed: Boolean, transmitted: Boolean, readBackAvailable: Boolean, readBackMatches: Boolean): ProfileVerdict = when {
        sessionFailed && !transmitted -> ProfileVerdict.NOT_SENT
        !readBackAvailable -> ProfileVerdict.UNCERTAIN
        readBackMatches -> ProfileVerdict.SUCCESS
        else -> ProfileVerdict.VERIFY_FAILED
    }

    fun alertDto(a: Alert): AlertDto? {
        val type = a.alertType ?: return null
        val category = when (a.alertCategory) {
            AlertCategory.REMINDER -> "reminder"
            AlertCategory.MAINTENANCE -> "maintenance"
            AlertCategory.WARNING -> "warning"
            AlertCategory.ERROR -> "error"
            null -> return null
        }
        val status = if (a.alertStatus == AlertStatus.SNOOZED) "snoozed" else "active"
        val tbr = if (type == AlertType.REMINDER_07 || type == AlertType.WARNING_36) a.tBRAmount else null
        val remaining = if (type == AlertType.WARNING_31) a.cartridgeAmount else null
        return AlertDto(a.alertId, type.name, category, status, tbr, remaining)
    }

    /** Local pump time fields to a UTC instant, using the phone zone (the bridge keeps pump time = phone local time). */
    fun toInstant(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Instant? = try {
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant()
    } catch (_: Exception) {
        null
    }

    fun iso(i: Instant): String = i.truncatedTo(ChronoUnit.SECONDS).toString()
    fun isoLocal(zone: ZoneId, i: Instant): String = LocalDateTime.ofInstant(i, zone).truncatedTo(ChronoUnit.SECONDS).toString()

    /**
     * Maps a pump history event. [driftSeconds] (pump clock minus real time) is subtracted from the
     * event time; [programmedLookup] resolves the programmed amount for a delivered bolus.
     * Returns null for events with an unparseable timestamp.
     */
    fun historyEvent(
        e: HistoryEvent, zone: ZoneId, driftSeconds: Long,
        programmedLookup: (Int) -> Double?, rememberProgrammed: (Int, Double) -> Unit
    ): HistoryEventDto? {
        val local = toInstant(zone, e.eventYear, e.eventMonth, e.eventDay, e.eventHour, e.eventMinute, e.eventSecond) ?: return null
        val time = local.minusSeconds(driftSeconds)
        val data: JsonObject
        val type: String
        when (e) {
            is BolusProgrammedEvent -> {
                type = "bolusProgrammed"
                val amount = e.immediateAmount + e.extendedAmount
                e.bolusID.let { rememberProgrammed(it, amount) }
                data = buildJsonObject {
                    put("bolusId", e.bolusID); put("bolusType", bolusTypeWire(e.bolusType)); put("amount", amount)
                }
            }
            is BolusDeliveredEvent -> {
                type = "bolusDelivered"
                var start = toInstant(zone, e.eventYear, e.eventMonth, e.eventDay, e.startHour, e.startMinute, e.startSecond)
                if (start != null && start.isAfter(local)) start = start.minus(1, ChronoUnit.DAYS)
                val delivered = e.immediateAmount + e.extendedAmount
                val programmed = programmedLookup(e.bolusID)
                data = buildJsonObject {
                    put("bolusId", e.bolusID); put("bolusType", bolusTypeWire(e.bolusType))
                    put("programmed", programmed?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("delivered", delivered)
                    put("start", start?.minusSeconds(driftSeconds)?.let { JsonPrimitive(iso(it)) } ?: JsonNull)
                    put("end", iso(time))
                }
            }
            is StartOfTBREvent -> {
                type = "tbrStart"
                data = buildJsonObject { put("percent", e.amount); put("duration", e.duration) }
            }
            is EndOfTBREvent -> {
                type = "tbrEnd"
                data = buildJsonObject { put("percent", e.amount); put("duration", e.duration) }
            }
            is BasalDeliveryChangedEvent -> {
                type = "basalRateChanged"
                data = buildJsonObject { put("oldRate", e.oldBasalRate); put("newRate", e.newBasalRate) }
            }
            is OperatingModeChangedEvent -> {
                type = "operatingModeChanged"
                data = buildJsonObject {
                    put("oldMode", modeWire(e.oldValue)?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("newMode", modeWire(e.newValue)?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            }
            is CartridgeInsertedEvent -> { type = "cartridgeInserted"; data = buildJsonObject { put("units", JsonNull) } }
            is CartridgeRemovedEvent -> { type = "cartridgeRemoved"; data = JsonObject(emptyMap()) }
            is TubeFilledEvent -> { type = "tubeFilled"; data = buildJsonObject { put("units", e.amount) } }
            is CannulaFilledEvent -> { type = "cannulaFilled"; data = buildJsonObject { put("units", e.amount) } }
            is TotalDailyDoseEvent -> {
                type = "totalDailyDose"
                val date = try { LocalDate.of(e.totalYear, e.totalMonth, e.totalDay).toString() } catch (_: Exception) { null }
                data = buildJsonObject {
                    put("basal", e.basalTotal); put("bolus", e.bolusTotal)
                    put("total", Math.round((e.basalTotal + e.bolusTotal) * 100.0) / 100.0)
                    put("date", date?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            }
            is OccurrenceOfAlertEvent -> {
                type = "alert"
                val category = when (e) {
                    is OccurrenceOfWarningEvent -> "warning"
                    is OccurrenceOfErrorEvent -> "error"
                    is OccurrenceOfMaintenanceEvent -> "maintenance"
                    else -> "warning"
                }
                data = buildJsonObject {
                    put("alertId", e.alertID)
                    put("alertType", e.alertType?.name?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("category", category)
                }
            }
            is DateTimeChangedEvent -> {
                type = "dateTimeChanged"
                val old = toInstant(zone, e.beforeYear, e.beforeMonth, e.beforeDay, e.beforeHour, e.beforeMinute, e.beforeSecond)
                data = buildJsonObject {
                    put("old", old?.let { JsonPrimitive(isoLocal(zone, it)) } ?: JsonNull)
                    put("new", isoLocal(zone, local))
                }
            }
            else -> {
                type = "other"
                data = buildJsonObject { put("raw", e.javaClass.simpleName) }
            }
        }
        return HistoryEventDto(e.eventPosition, iso(time), isoLocal(zone, local), type, data)
    }

    /**
     * Turns a failure of a pump request into the bridge error model. [transmitted] says whether the
     * request had been handed to the Bluetooth writer: with a link error after that, the outcome is
     * unknown (uncertain). A pump-side error reply means the command was refused (certain).
     */
    fun mapFailure(e: Throwable, transmitted: Boolean, mayHaveEffect: Boolean): PumpOpException = when (e) {
        is PumpOpException -> e
        is PumpStoppedException -> PumpOpException(ErrorCode.PUMP_SUSPENDED, "The pump is stopped", cause = e)
        is PumpBusyException, is MaximumNumberOfBolusTypeAlreadyRunningException ->
            PumpOpException(ErrorCode.PUMP_BUSY, "The pump is busy", cause = e)
        is BolusAmountNotInRangeException -> PumpOpException(ErrorCode.LIMIT_EXCEEDED, "The pump rejected the bolus amount", cause = e)
        is AppLayerErrorException ->
            PumpOpException(ErrorCode.INTERNAL, "The pump rejected the command (${e.javaClass.simpleName}, code ${e.errorCode})", cause = e)
        is DisconnectedException, is ConnectionFailedException ->
            PumpOpException(ErrorCode.PUMP_UNREACHABLE, "The pump is not reachable", cause = e)
        else -> {
            val uncertain = mayHaveEffect && transmitted
            PumpOpException(
                ErrorCode.PUMP_UNREACHABLE,
                if (uncertain) "The connection to the pump was lost after the command was sent; its outcome is unknown"
                else "The pump did not answer (${e.javaClass.simpleName})",
                uncertain = uncertain, cause = e
            )
        }
    }
}
