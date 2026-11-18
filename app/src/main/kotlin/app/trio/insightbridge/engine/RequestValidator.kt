/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import app.trio.insightbridge.protocol.BasalSegment
import app.trio.insightbridge.protocol.ErrorCode
import app.trio.insightbridge.protocol.PumpOpException
import app.trio.insightbridge.protocol.Request
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** Pump limits used for validation. All values exact decimals. */
data class PumpLimits(
    val maxBolus: BigDecimal,
    val maxBasal: BigDecimal,
    val minBasal: BigDecimal,
    val bolusStep: BigDecimal = BigDecimal("0.05"),
    val basalStep: BigDecimal = BigDecimal("0.01")
)

/**
 * PROTOCOL.md section 5.1 validation rules. Every failure is `invalidArgs`; nothing is ever clamped
 * or rounded. Pure functions, no pump access.
 */
object RequestValidator {

    val ABSOLUTE_MAX_BOLUS: BigDecimal = BigDecimal(25)
    /** Insight basal rates above this are only deliverable in 0.1 U/h steps (AndroidAPS rounding rule). */
    val BASAL_COARSE_THRESHOLD: BigDecimal = BigDecimal(5)
    val BASAL_COARSE_STEP: BigDecimal = BigDecimal("0.1")
    const val MAX_CLOCK_SKEW_SECONDS = 600L
    const val MAX_HISTORY = 200
    const val PROTOCOL_VERSION = 2

    private fun bad(msg: String): Nothing = throw PumpOpException(ErrorCode.INVALID_ARGS, msg)

    fun isMultiple(value: BigDecimal, step: BigDecimal): Boolean =
        step.signum() > 0 && value.remainder(step).signum() == 0

    /**
     * The Insight's bolus resolution grows with the amount (AndroidAPS `DoseStepSize.InsightBolus`):
     * up to 2 U in 0.05 U, above 2 U up to 5 U in 0.1 U, above 5 U below 10 U in 0.2 U, from 10 U in 0.5 U.
     * [baseStep] is the pump's finest step (`limits.bolusStep`, 0.05); it is what the status reports, i.e.
     * the step valid for small amounts. A pump that reports a coarser base step wins.
     */
    fun bolusStepFor(amount: BigDecimal, baseStep: BigDecimal): BigDecimal {
        val grid = when {
            amount <= BigDecimal(2) -> BigDecimal("0.05")
            amount <= BigDecimal(5) -> BigDecimal("0.1")
            amount < BigDecimal(10) -> BigDecimal("0.2")
            else -> BigDecimal("0.5")
        }
        return grid.max(baseStep)
    }

    fun validateBolus(amount: BigDecimal, limits: PumpLimits) {
        if (amount.signum() <= 0) bad("amount must be greater than 0")
        val cap = limits.maxBolus.min(ABSOLUTE_MAX_BOLUS)
        if (amount > cap) bad("amount exceeds the maximum bolus of ${cap.toPlainString()} U")
        val step = bolusStepFor(amount, limits.bolusStep)
        if (!isMultiple(amount, step)) bad("amount must be a multiple of ${step.toPlainString()} U at this size")
    }

    fun validateTbr(percent: Int, duration: Int) {
        if (percent !in 0..250) bad("percent must be between 0 and 250")
        if (percent % 10 != 0) bad("percent must be a multiple of 10")
        if (percent == 100) bad("percent 100 is invalid, use cancelTBR")
        if (duration !in 15..1440) bad("duration must be between 15 and 1440 minutes")
        if (duration % 15 != 0) bad("duration must be a multiple of 15 minutes")
    }

    fun validateBasalProfile(segments: List<BasalSegment>, limits: PumpLimits) {
        if (segments.isEmpty() || segments.size > 24) bad("1 to 24 segments required")
        var total = 0
        segments.forEach { s ->
            if (s.durationMinutes <= 0 || s.durationMinutes % 60 != 0) bad("segment durations must be positive multiples of 60 minutes")
            total += s.durationMinutes
            if (total > 1440) bad("segments must sum to 1440 minutes")
            if (s.rate < limits.minBasal) bad("rate below the pump minimum basal of ${limits.minBasal.toPlainString()} U/h")
            if (s.rate > limits.maxBasal) bad("rate above the pump maximum basal of ${limits.maxBasal.toPlainString()} U/h")
            val step = if (s.rate > BASAL_COARSE_THRESHOLD) BASAL_COARSE_STEP.max(limits.basalStep) else limits.basalStep
            if (!isMultiple(s.rate, step)) bad("rate must be a multiple of ${step.toPlainString()} U/h")
        }
        if (total != 1440) bad("segments must sum to 1440 minutes")
    }

    fun validateDateTime(utc: Instant, tzOffsetMinutes: Int, now: Instant) {
        if (tzOffsetMinutes !in -840..840) bad("tzOffsetMinutes out of range")
        if (Duration.between(now, utc).abs().seconds > MAX_CLOCK_SKEW_SECONDS) bad("utc differs too much from the bridge clock")
    }

    fun validateHistory(since: Long?, max: Int) {
        if (since != null && since < 0) bad("sinceSequence must not be negative")
        if (max !in 1..MAX_HISTORY) bad("max must be between 1 and $MAX_HISTORY")
    }

    fun validateBolusId(bolusId: Int) {
        if (bolusId !in 0..0xFFFF) bad("bolusId out of range")
    }

    fun validateAlert(alertId: Int, alertType: String, knownTypes: Set<String>) {
        if (alertId !in 0..0xFFFF) bad("alertId out of range")
        if (alertType !in knownTypes) bad("unknown alertType")
    }

    /** Static validations that need no pump limits. */
    fun validateStatic(request: Request, now: Instant, knownAlertTypes: Set<String>) {
        when (request) {
            is Request.SetTbr -> validateTbr(request.percent, request.duration)
            is Request.SetDateTime -> validateDateTime(request.utc, request.tzOffsetMinutes, now)
            is Request.ReadHistory -> validateHistory(request.sinceSequence, request.max)
            is Request.CancelBolus -> validateBolusId(request.bolusId)
            is Request.ConfirmAlert -> validateAlert(request.alertId, request.alertType, knownAlertTypes)
            is Request.SnoozeAlert -> validateAlert(request.alertId, request.alertType, knownAlertTypes)
            is Request.Hello -> if (request.protocol != PROTOCOL_VERSION) throw PumpOpException(ErrorCode.UNSUPPORTED_PROTOCOL, "only protocol 2 is supported")
            else -> Unit
        }
    }
}
