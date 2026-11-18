/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import app.trio.insightbridge.backend.AlertCatalog
import app.trio.insightbridge.backend.PumpBackend
import app.trio.insightbridge.protocol.ErrorCode
import app.trio.insightbridge.protocol.PumpOpException
import app.trio.insightbridge.protocol.Request
import app.trio.insightbridge.protocol.RequestParser
import app.trio.insightbridge.protocol.Wire
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * Decrypted request -> validation -> idempotency -> backend -> response.
 *
 * Safety invariants (PROTOCOL.md section 7) enforced here:
 *  - out-of-range values are rejected, never clamped ([RequestValidator]);
 *  - dosing commands are recorded IN_FLIGHT before they reach the pump and are never retried;
 *  - a repeated requestId returns the stored outcome without touching the pump;
 *  - dosing commands are serialised so two cannot interleave on the pump link.
 */
class OpDispatcher(
    private val backend: () -> PumpBackend?,
    private val store: IdempotencyStore,
    private val clock: () -> Long,
    private val bridgeVersion: String,
    private val bluetoothOn: () -> Boolean = { true },
    private val onRequest: (String) -> Unit = {},
    private val dosingTimeoutMillis: Long = 40_000,
    private val readTimeoutMillis: Long = 25_000
) {

    private val dosingMutex = Mutex()

    /** Handles one request frame and returns the response object. Never throws (except cancellation). */
    suspend fun handle(frame: JsonObject): JsonObject {
        val id = RequestParser.idOf(frame) ?: 0L
        return try {
            val request = RequestParser.parseRequest(frame)
            onRequest(request.op)
            RequestValidator.validateStatic(request, Instant.ofEpochMilli(clock()), AlertCatalog.knownTypes)
            execute(id, request)
        } catch (e: PumpOpException) {
            Wire.error(id, e.code, e.message ?: e.code.wire, e.uncertain)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Wire.error(id, ErrorCode.INTERNAL, "Internal error: ${e.javaClass.simpleName}")
        }
    }

    private fun requireBackend(): PumpBackend {
        val b = backend()
        if (b == null || !b.isPaired) throw PumpOpException(ErrorCode.NOT_PAIRED, "The bridge has no pump pairing")
        return b
    }

    private suspend fun execute(id: Long, request: Request): JsonObject = when (request) {
        is Request.Hello -> Wire.ok(id, Wire.helloResult(bridgeVersion, backend()?.isPaired == true))
        is Request.GetRequestOutcome -> {
            val l = store.lookup(request.requestId)
            val o = l.outcome
            val completedAt = l.completedAt?.let { Instant.ofEpochMilli(it).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString() }
            Wire.ok(
                id,
                when {
                    o == null -> Wire.outcomeResult(l.state.wire, null, null, null, false, null)
                    o.ok -> Wire.outcomeResult(l.state.wire, o.result ?: Wire.emptyResult(), null, null, false, completedAt)
                    else -> Wire.outcomeResult(l.state.wire, null, o.errorCode ?: ErrorCode.INTERNAL, o.message, o.uncertain, completedAt)
                }
            )
        }
        is Request.PumpPairingStatus -> {
            val b = backend()
            Wire.ok(id, Wire.pairingStatusResult(b?.isPaired == true, b?.serial, bluetoothOn()))
        }
        is Request.GetStatus -> {
            val b = requireBackend()
            Wire.ok(id, Wire.toJson(readOp { b.getStatus() }).let { it as JsonObject })
        }
        is Request.ReadHistory -> {
            val b = requireBackend()
            Wire.ok(id, Wire.historyResult(readOp { b.readHistory(request.sinceSequence, request.max) }))
        }
        is Request.ConfirmAlert -> {
            val b = requireBackend()
            readOp { b.confirmAlert(request.alertId, request.alertType) }
            Wire.ok(id, Wire.emptyResult())
        }
        is Request.SnoozeAlert -> {
            val b = requireBackend()
            readOp { b.snoozeAlert(request.alertId, request.alertType) }
            Wire.ok(id, Wire.emptyResult())
        }
        is Request.SetDateTime -> {
            val b = requireBackend()
            readOp { b.setDateTime(request.utc, request.tzOffsetMinutes) }
            Wire.ok(id, Wire.emptyResult())
        }
        is Request.CancelBolus -> {
            val b = requireBackend()
            // Cancelling is time critical: not serialised behind other dosing commands.
            val delivered = withTimeoutMapped(dosingTimeoutMillis, uncertainOnTimeout = true) { b.cancelBolus(request.bolusId) }
            Wire.ok(id, Wire.cancelBolusResult(delivered))
        }
        is Request.DeliverBolus -> idempotent(id, request, fingerprint = "amount=${request.amount.stripTrailingZeros().toPlainString()}") { b ->
            RequestValidator.validateBolus(request.amount, b.limits())
        } run { b ->
            val bolusId = b.deliverBolus(request.amount)
            Wire.bolusResult(bolusId, request.amount)
        }
        is Request.SetTbr -> idempotent(id, request, "percent=${request.percent};duration=${request.duration}") {} run { b ->
            b.setTbr(request.percent, request.duration)
            Wire.tbrResult(request.percent, request.duration)
        }
        is Request.CancelTbr -> idempotent(id, request, "") {} run { b ->
            b.cancelTbr()
            Wire.emptyResult()
        }
        is Request.SetOperatingMode -> idempotent(id, request, "started=${request.started}") {} run { b ->
            b.setOperatingMode(request.started)
            Wire.modeResult(request.started)
        }
        is Request.SetBasalProfile -> idempotent(
            id, request,
            request.segments.joinToString("|") { "${it.durationMinutes}:${it.rate.stripTrailingZeros().toPlainString()}" }
        ) { b ->
            RequestValidator.validateBasalProfile(request.segments, b.limits())
        } run { b ->
            Wire.profileResult(b.setBasalProfile(request.segments))
        }
    }

    private suspend fun <T> readOp(block: suspend () -> T): T = withTimeoutMapped(readTimeoutMillis, uncertainOnTimeout = false, block = block)

    private suspend fun <T> withTimeoutMapped(ms: Long, uncertainOnTimeout: Boolean, block: suspend () -> T): T = try {
        withTimeout(ms) { block() }
    } catch (e: TimeoutCancellationException) {
        throw PumpOpException(ErrorCode.PUMP_UNREACHABLE, "The pump did not answer in time", uncertain = uncertainOnTimeout)
    }

    private class Idempotent(
        val dispatcher: OpDispatcher,
        val id: Long,
        val request: Request,
        val fingerprint: String,
        val validate: suspend (PumpBackend) -> Unit
    ) {

        suspend infix fun run(action: suspend (PumpBackend) -> JsonObject): JsonObject = dispatcher.runIdempotent(this, action)
    }

    private fun idempotent(
        id: Long, request: Request, fingerprint: String,
        validate: suspend (PumpBackend) -> Unit
    ) = Idempotent(this, id, request, fingerprint, validate)

    private suspend fun runIdempotent(ctx: Idempotent, action: suspend (PumpBackend) -> JsonObject): JsonObject {
        val b = requireBackend()
        val requestId = ctx.request.requestIdOrNull!!
        // Validation that needs pump limits happens before anything is recorded, so a rejected
        // request never consumes (or is replayed as) a requestId.
        withTimeoutMapped(readTimeoutMillis, false) { ctx.validate(b) }

        when (val begin = store.begin(requestId, ctx.request.op, ctx.fingerprint)) {
            is IdempotencyStore.Begin.Replay -> return toResponse(ctx.id, begin.outcome)
            is IdempotencyStore.Begin.Mismatch ->
                throw PumpOpException(ErrorCode.INVALID_ARGS, "requestId was already used for a different command")
            is IdempotencyStore.Begin.InFlight -> return toResponse(ctx.id, begin.deferred.await())
            is IdempotencyStore.Begin.Proceed -> Unit
        }

        val outcome = try {
            dosingMutex.withLock {
                val result = withTimeoutMapped(dosingTimeoutMillis, uncertainOnTimeout = true) { action(b) }
                StoredOutcome(true, result, null, null, false)
            }
        } catch (e: PumpOpException) {
            StoredOutcome(false, null, e.code, e.message, e.uncertain)
        } catch (e: CancellationException) {
            // Leave the entry IN_FLIGHT: it becomes an uncertain outcome after a restart.
            throw e
        } catch (e: Exception) {
            // Unknown failure after the command may have been sent: be conservative.
            StoredOutcome(false, null, ErrorCode.INTERNAL, "Internal error: ${e.javaClass.simpleName}", true)
        }
        store.complete(requestId, outcome)
        return toResponse(ctx.id, outcome)
    }

    private fun toResponse(id: Long, o: StoredOutcome): JsonObject =
        if (o.ok) Wire.ok(id, o.result ?: Wire.emptyResult())
        else Wire.error(id, o.errorCode ?: ErrorCode.INTERNAL, o.message ?: "error", o.uncertain)
}
