/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.backend

import app.aaps.pump.insight.app_layer.history.history_events.BolusDeliveredEvent
import app.aaps.pump.insight.app_layer.history.history_events.StartOfTBREvent
import app.aaps.pump.insight.descriptors.Alert
import app.aaps.pump.insight.descriptors.AlertCategory
import app.aaps.pump.insight.descriptors.AlertStatus
import app.aaps.pump.insight.descriptors.AlertType
import app.aaps.pump.insight.descriptors.OperatingMode
import app.aaps.pump.insight.exceptions.ConnectionLostException
import app.aaps.pump.insight.exceptions.DisconnectedException
import app.aaps.pump.insight.exceptions.TimeoutException
import app.aaps.pump.insight.exceptions.app_layer_errors.BolusAmountNotInRangeException
import app.aaps.pump.insight.exceptions.app_layer_errors.MaximumNumberOfBolusTypeAlreadyRunningException
import app.aaps.pump.insight.exceptions.app_layer_errors.PumpStoppedException
import app.aaps.pump.insight.exceptions.app_layer_errors.UnknownCommandException
import app.trio.insightbridge.backend.sight.SightMapping
import app.trio.insightbridge.protocol.ErrorCode
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.jupiter.api.Test

class SightMappingTest {

    @Test fun everyAndroidApsAlertTypeIsKnownAndCategorised() {
        assertThat(AlertCatalog.knownTypes).hasSize(AlertType.entries.size)
        assertThat(AlertCatalog.knownTypes).containsAtLeast("REMINDER_01", "WARNING_31", "MAINTENANCE_21", "ERROR_6")
        assertThat(AlertCatalog.categoryOf("WARNING_31")).isEqualTo("warning")
        assertThat(AlertCatalog.categoryOf("MAINTENANCE_24")).isEqualTo("maintenance")
        assertThat(AlertCatalog.categoryOf("ERROR_13")).isEqualTo("error")
        assertThat(AlertCatalog.categoryOf("REMINDER_07")).isEqualTo("reminder")
    }

    @Test fun alertMappingCarriesCategoryStatusAndExtras() {
        val a = Alert().apply {
            alertId = 5; alertType = AlertType.WARNING_31; alertCategory = AlertCategory.WARNING
            alertStatus = AlertStatus.SNOOZED; cartridgeAmount = 17.5
        }
        val d = SightMapping.alertDto(a)!!
        assertThat(d.alertType).isEqualTo("WARNING_31"); assertThat(d.category).isEqualTo("warning")
        assertThat(d.status).isEqualTo("snoozed"); assertThat(d.remainingInsulin).isEqualTo(17.5); assertThat(d.tbrPercent).isNull()
        val t = Alert().apply { alertId = 1; alertType = AlertType.WARNING_36; alertCategory = AlertCategory.WARNING; alertStatus = AlertStatus.ACTIVE; tBRAmount = 150 }
        assertThat(SightMapping.alertDto(t)!!.tbrPercent).isEqualTo(150)
        assertThat(SightMapping.alertDto(Alert())).isNull()
    }

    @Test fun modes() {
        assertThat(SightMapping.modeWire(OperatingMode.STARTED)).isEqualTo("started")
        assertThat(SightMapping.modeWire(OperatingMode.STOPPED)).isEqualTo("stopped")
        assertThat(SightMapping.modeWire(OperatingMode.PAUSED)).isEqualTo("paused")
        assertThat(SightMapping.modeWire(null)).isNull()
    }

    @Test fun pumpRefusalsAreCertain() {
        val stopped = SightMapping.mapFailure(PumpStoppedException(1), transmitted = true, mayHaveEffect = true)
        assertThat(stopped.code).isEqualTo(ErrorCode.PUMP_SUSPENDED); assertThat(stopped.uncertain).isFalse()
        assertThat(SightMapping.mapFailure(MaximumNumberOfBolusTypeAlreadyRunningException(1), true, true).code).isEqualTo(ErrorCode.PUMP_BUSY)
        assertThat(SightMapping.mapFailure(BolusAmountNotInRangeException(1), true, true).code).isEqualTo(ErrorCode.LIMIT_EXCEEDED)
        val other = SightMapping.mapFailure(UnknownCommandException(1), true, true)
        assertThat(other.code).isEqualTo(ErrorCode.INTERNAL); assertThat(other.uncertain).isFalse()
    }

    @Test fun notSentMeansCertainSentAndLostMeansUncertain() {
        val notSent = SightMapping.mapFailure(DisconnectedException(), transmitted = false, mayHaveEffect = true)
        assertThat(notSent.code).isEqualTo(ErrorCode.PUMP_UNREACHABLE); assertThat(notSent.uncertain).isFalse()
        val lostBefore = SightMapping.mapFailure(ConnectionLostException(), transmitted = false, mayHaveEffect = true)
        assertThat(lostBefore.uncertain).isFalse()
        val lostAfter = SightMapping.mapFailure(ConnectionLostException(), transmitted = true, mayHaveEffect = true)
        assertThat(lostAfter.uncertain).isTrue()
        val timeout = SightMapping.mapFailure(TimeoutException(), transmitted = true, mayHaveEffect = true)
        assertThat(timeout.uncertain).isTrue()
        // read-only commands are never uncertain
        assertThat(SightMapping.mapFailure(TimeoutException(), transmitted = true, mayHaveEffect = false).uncertain).isFalse()
    }

    @Test fun historyEventsMapToSpecTypesWithUtcTimes() {
        val tbr = StartOfTBREvent()
        // events are built by the real parser in AAPS tests; here we only check the mapping shell with a parsed header
        val header = app.aaps.pump.insight.utils.ByteBuf(32).apply {
            // BOC: 20 26 11 18 | skip | 10 30 00 | position 9876 LE
            putByte(0x20); putByte(0x26); putByte(0x11); putByte(0x18); putByte(0x00)
            putByte(0x10); putByte(0x30); putByte(0x00)
            putUInt32LE(9876)
            putUInt16LE(120); putUInt16LE(60)
        }
        tbr.parseHeader(header); tbr.parse(header)
        val dto = SightMapping.historyEvent(tbr, ZoneId.of("UTC"), 0, { null }, { _, _ -> })!!
        assertThat(dto.seq).isEqualTo(9876)
        assertThat(dto.type).isEqualTo("tbrStart")
        assertThat(dto.time).isEqualTo("2026-11-18T10:30:00Z")
        assertThat(dto.pumpLocal).isEqualTo("2026-11-18T10:30")
        assertThat(dto.data.toString()).isEqualTo("""{"percent":120,"duration":60}""")
        // drift correction: a pump clock 30 s ahead
        assertThat(SightMapping.historyEvent(tbr, ZoneId.of("UTC"), 30, { null }, { _, _ -> })!!.time).isEqualTo("2026-11-18T10:29:30Z")
        @Suppress("UNUSED_VARIABLE") val unused = BolusDeliveredEvent()
    }
}
