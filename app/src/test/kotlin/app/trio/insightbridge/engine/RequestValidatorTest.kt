/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import app.trio.insightbridge.protocol.BasalSegment
import app.trio.insightbridge.protocol.ErrorCode
import app.trio.insightbridge.protocol.PumpOpException
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RequestValidatorTest {

    private val limits = PumpLimits(BigDecimal("10.0"), BigDecimal("2.5"), BigDecimal("0.02"))

    private fun assertInvalid(block: () -> Unit) {
        val e = assertThrows<PumpOpException> { block() }
        assertThat(e.code).isEqualTo(ErrorCode.INVALID_ARGS)
        assertThat(e.uncertain).isFalse()
    }

    private fun bolus(s: String) = RequestValidator.validateBolus(BigDecimal(s), limits)

    @Test fun bolusAcceptsMultiplesOfStepUpToMax() {
        bolus("0.05"); bolus("0.10"); bolus("1.35"); bolus("2"); bolus("10.0"); bolus("9.95")
    }

    @Test fun bolusRejectsZeroNegativeAndNonMultiples() {
        assertInvalid { bolus("0") }
        assertInvalid { bolus("-1") }
        assertInvalid { bolus("0.07") }
        assertInvalid { bolus("0.051") }
        assertInvalid { bolus("1.001") }
    }

    @Test fun bolusAboveMaxIsRejectedNotClamped() {
        assertInvalid { bolus("10.05") }
        assertInvalid { bolus("11") }
    }

    @Test fun bolusIsCappedAtAbsolute25EvenIfPumpAllowsMore() {
        val big = PumpLimits(BigDecimal("50"), BigDecimal("5"), BigDecimal("0.02"))
        RequestValidator.validateBolus(BigDecimal("25"), big)
        assertInvalid { RequestValidator.validateBolus(BigDecimal("25.05"), big) }
    }

    @Test fun bolusUsesLimitsStep() {
        val coarse = limits.copy(bolusStep = BigDecimal("0.1"))
        RequestValidator.validateBolus(BigDecimal("0.2"), coarse)
        assertInvalid { RequestValidator.validateBolus(BigDecimal("0.15"), coarse) }
    }

    @Test fun tbrValidCombinations() {
        for (p in listOf(0, 10, 90, 110, 250)) for (d in listOf(15, 30, 60, 1440)) RequestValidator.validateTbr(p, d)
    }

    @Test fun tbrRejectsBadPercent() {
        for (p in listOf(-10, 5, 100, 105, 251, 260, 300)) assertInvalid { RequestValidator.validateTbr(p, 30) }
    }

    @Test fun tbrRejectsBadDuration() {
        for (d in listOf(0, 10, 14, 16, 20, 1441, 1455, -15)) assertInvalid { RequestValidator.validateTbr(50, d) }
    }

    private fun seg(vararg pairs: Pair<Int, String>) = pairs.map { BasalSegment(it.first, BigDecimal(it.second)) }

    @Test fun profileValid() {
        RequestValidator.validateBasalProfile(seg(1440 to "0.80"), limits)
        RequestValidator.validateBasalProfile(List(24) { BasalSegment(60, BigDecimal("0.02")) }, limits)
        RequestValidator.validateBasalProfile(seg(360 to "0.5", 1080 to "2.5"), limits)
    }

    @Test fun profileRejectsStructure() {
        assertInvalid { RequestValidator.validateBasalProfile(emptyList(), limits) }
        assertInvalid { RequestValidator.validateBasalProfile(List(25) { BasalSegment(60, BigDecimal("1")) }, limits) }
        assertInvalid { RequestValidator.validateBasalProfile(seg(1380 to "1"), limits) } // sums to 1380
        assertInvalid { RequestValidator.validateBasalProfile(seg(1440 to "1", 60 to "1"), limits) } // exceeds
        assertInvalid { RequestValidator.validateBasalProfile(seg(30 to "1", 1410 to "1"), limits) } // not hourly
        assertInvalid { RequestValidator.validateBasalProfile(seg(0 to "1", 1440 to "1"), limits) } // zero duration
    }

    @Test fun profileRejectsRates() {
        assertInvalid { RequestValidator.validateBasalProfile(seg(1440 to "0.01"), limits) } // below min
        assertInvalid { RequestValidator.validateBasalProfile(seg(1440 to "2.51"), limits) } // above max
        assertInvalid { RequestValidator.validateBasalProfile(seg(1440 to "0.805"), limits) } // not on 0.01 step
    }

    @Test fun profileUsesCoarseStepAboveFiveUnitsPerHour() {
        val wide = limits.copy(maxBasal = BigDecimal("10"))
        RequestValidator.validateBasalProfile(seg(1440 to "5.00"), wide)
        RequestValidator.validateBasalProfile(seg(1440 to "5.10"), wide)
        assertInvalid { RequestValidator.validateBasalProfile(seg(1440 to "5.05"), wide) }
    }

    @Test fun dateTimeWithinSkew() {
        val now = Instant.parse("2026-11-18T10:00:00Z")
        RequestValidator.validateDateTime(now.plusSeconds(500), 60, now)
        assertInvalid { RequestValidator.validateDateTime(now.plusSeconds(601), 60, now) }
        assertInvalid { RequestValidator.validateDateTime(now.minusSeconds(3600), 60, now) }
        assertInvalid { RequestValidator.validateDateTime(now, 841, now) }
        assertInvalid { RequestValidator.validateDateTime(now, -841, now) }
    }

    @Test fun historyArguments() {
        RequestValidator.validateHistory(null, 1); RequestValidator.validateHistory(0, 200)
        assertInvalid { RequestValidator.validateHistory(null, 0) }
        assertInvalid { RequestValidator.validateHistory(null, 201) }
        assertInvalid { RequestValidator.validateHistory(-1, 10) }
    }

    @Test fun alertArguments() {
        val known = setOf("WARNING_31")
        RequestValidator.validateAlert(1, "WARNING_31", known)
        assertInvalid { RequestValidator.validateAlert(1, "NOPE", known) }
        assertInvalid { RequestValidator.validateAlert(-1, "WARNING_31", known) }
        assertInvalid { RequestValidator.validateAlert(70000, "WARNING_31", known) }
    }

    @Test fun bolusIdRange() {
        RequestValidator.validateBolusId(0); RequestValidator.validateBolusId(65535)
        assertInvalid { RequestValidator.validateBolusId(65536) }
        assertInvalid { RequestValidator.validateBolusId(-1) }
    }
}
