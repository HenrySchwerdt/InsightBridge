package app.trio.insightbridge.engine

import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.backend.AlertCatalog
import app.trio.insightbridge.backend.BolusStarted
import app.trio.insightbridge.backend.PumpBackend
import app.trio.insightbridge.protocol.ErrorCode
import app.trio.insightbridge.protocol.PumpLinkState
import app.trio.insightbridge.protocol.PumpOpException
import app.trio.insightbridge.protocol.Request
import app.trio.insightbridge.protocol.RequestParser
import app.trio.insightbridge.protocol.Wire
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * Decrypted request -> idempotency -> validation -> backend -> response.
 *
 * Safety invariants (PROTOCOL.md section 7) enforced here:
 *  - the requestId is recorded (persisted, "inProgress") BEFORE anything else happens, in particular before
 *    any backend access: a replay can never be answered by a fresh failure and `getRequestOutcome` never says
 *    `unknown` for a command that is about to run;
 *  - out-of-range values are rejected, never clamped ([RequestValidator]); a rejection is recorded as the
 *    (certain) outcome of that requestId;
 *  - dosing commands are never retried; a stopped coroutine records an uncertain outcome instead of leaving
 *    the entry in flight;
 *  - state-changing commands (including `setDateTime`) are serialised so they cannot interleave on the link.
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
    private val startedAt = clock()

    /** Handles one request frame and returns the response object. Never throws (except cancellation). */
    suspend fun handle(frame: JsonObject): JsonObject {
        val id = RequestParser.idOf(frame) ?: 0L
        return try {
            val request = RequestParser.parseRequest(frame)
            onRequest(request.op)
            if (request.requestIdOrNull == null) {
                RequestValidator.validateStatic(request, Instant.ofEpochMilli(clock()), AlertCatalog.knownTypes)
            }
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
        // Liveness only: reads cached state, never suspends, never touches the pump link.
        is Request.Ping -> {
            val b = backend()
            Wire.ok(
                id,
                Wire.pingResult(
                    ((clock() - startedAt) / 1000).coerceAtLeast(0),
                    b?.linkState?.value?.wire ?: PumpLinkState.NOT_PAIRED.wire,
                    b?.cachedStatus()?.timestamp
                )
            )
        }
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
            // Changes the pump clock: serialised with the dosing commands, and a timeout / link loss after
            // sending is uncertain (it may have been applied).
            dosingMutex.withLock {
                withBudget(dosingTimeoutMillis, uncertainOnTimeout = true) { b.setDateTime(request.utc, request.tzOffsetMinutes) }
            }
            Wire.ok(id, Wire.emptyResult())
        }
        is Request.CancelBolus -> {
            val b = requireBackend()
            // Cancelling is time critical: not serialised behind other dosing commands.
            val delivered = withBudget(dosingTimeoutMillis, uncertainOnTimeout = true) { b.cancelBolus(request.bolusId) }
            Wire.ok(id, Wire.cancelBolusResult(delivered))
        }
        is Request.DeliverBolus -> idempotent(id, request, "amount=${request.amount.stripTrailingZeros().toPlainString()}", { b ->
            RequestValidator.validateBolus(request.amount, b.limits())
        }) { b ->
            val started = b.deliverBolus(request.amount)
            Wire.bolusResult(started.bolusId, started.amount)
        }
        is Request.SetTbr -> idempotent(id, request, "percent=${request.percent};duration=${request.duration}", {}) { b ->
            b.setTbr(request.percent, request.duration)
            Wire.tbrResult(request.percent, request.duration)
        }
        is Request.CancelTbr -> idempotent(id, request, "", {}) { b ->
            b.cancelTbr()
            Wire.emptyResult()
        }
        is Request.SetOperatingMode -> idempotent(id, request, "started=${request.started}", {}) { b ->
            b.setOperatingMode(request.started)
            Wire.modeResult(request.started)
        }
        is Request.SetBasalProfile -> idempotent(
            id, request,
            request.segments.joinToString("|") { "${it.durationMinutes}:${it.rate.stripTrailingZeros().toPlainString()}" },
            { b -> RequestValidator.validateBasalProfile(request.segments, b.limits()) }
        ) { b ->
            Wire.profileResult(b.setBasalProfile(request.segments))
        }
    }

    private suspend fun <T> readOp(block: suspend () -> T): T = withBudget(readTimeoutMillis, uncertainOnTimeout = false, block = block)

    /** Runs [block] with an [OpContext] budget; the backend shares the deadline between its inner steps. */
    private suspend fun <T> withBudget(ms: Long, uncertainOnTimeout: Boolean, late: (Result<Any?>) -> Unit = {}, block: suspend () -> T): T = try {
        withTimeout(ms + TIMEOUT_BACKSTOP_MS) {
            withContext(OpContext(clock() + ms, clock, late)) { block() }
        }
    } catch (e: TimeoutCancellationException) {
        throw PumpOpException(ErrorCode.PUMP_UNREACHABLE, "The pump did not answer in time", uncertain = uncertainOnTimeout)
    }

    private fun lateResult(request: Request, value: Any?): JsonObject = when {
        request is Request.DeliverBolus && value is BolusStarted -> Wire.bolusResult(value.bolusId, value.amount)
        request is Request.SetTbr -> Wire.tbrResult(request.percent, request.duration)
        request is Request.SetOperatingMode -> Wire.modeResult(request.started)
        request is Request.SetBasalProfile -> Wire.profileResult((value as? Int) ?: 1)
        else -> Wire.emptyResult()
    }

    private suspend fun idempotent(
        id: Long, request: Request, fingerprint: String,
        validateWithBackend: suspend (PumpBackend) -> Unit,
        action: suspend (PumpBackend) -> JsonObject
    ): JsonObject {
        val requestId = request.requestIdOrNull!!
        // 1. Record first. Replay / mismatch / in-flight are answered from the journal without any backend access.
        when (val begin = store.begin(requestId, request.op, fingerprint)) {
            is IdempotencyStore.Begin.Replay -> return toResponse(id, begin.outcome)
            is IdempotencyStore.Begin.Mismatch ->
                throw PumpOpException(ErrorCode.INVALID_ARGS, "requestId was already used for a different command")
            is IdempotencyStore.Begin.InFlight -> return toResponse(id, begin.deferred.await())
            is IdempotencyStore.Begin.Proceed -> Unit
        }

        var commandMayHaveReachedPump = false
        val late: (Result<Any?>) -> Unit = { r ->
            r.onSuccess { v -> store.amend(requestId, StoredOutcome(true, lateResult(request, v), null, null, false)) }
        }
        val outcome: StoredOutcome = try {
            RequestValidator.validateStatic(request, Instant.ofEpochMilli(clock()), AlertCatalog.knownTypes)
            val b = requireBackend()
            // Validation may read limits (and open the link): it runs inside the same budget as the command.
            val result = withBudget(dosingTimeoutMillis, uncertainOnTimeout = true, late = late) {
                validateWithBackend(b)
                dosingMutex.withLock {
                    commandMayHaveReachedPump = true
                    action(b)
                }
            }
            StoredOutcome(true, result, null, null, false)
        } catch (e: PumpOpException) {
            // A rejection before the command started is certain, whatever the exception says.
            StoredOutcome(false, null, e.code, e.message, e.uncertain && commandMayHaveReachedPump)
        } catch (e: CancellationException) {
            // The coroutine is being stopped (service stop / restart): never leave the entry in flight.
            withContext(NonCancellable) {
                store.complete(
                    requestId,
                    StoredOutcome(
                        false, null, ErrorCode.INTERNAL,
                        if (commandMayHaveReachedPump) "The bridge was stopped while the command was running; the pump may or may not have executed it"
                        else "The bridge was stopped before the command was sent",
                        uncertain = commandMayHaveReachedPump
                    )
                )
            }
            throw e
        } catch (e: Exception) {
            PumpLog.w("command failed unexpectedly: ${e.javaClass.simpleName}")
            StoredOutcome(false, null, ErrorCode.INTERNAL, "Internal error: ${e.javaClass.simpleName}", uncertain = commandMayHaveReachedPump)
        }
        store.complete(requestId, outcome)
        return toResponse(id, outcome)
    }

    private fun toResponse(id: Long, o: StoredOutcome): JsonObject =
        if (o.ok) Wire.ok(id, o.result ?: Wire.emptyResult())
        else Wire.error(id, o.errorCode ?: ErrorCode.INTERNAL, o.message ?: "error", o.uncertain)

    companion object {

        /** The OpContext budget is the real limit; this outer timeout is only a backstop against a stuck backend. */
        const val TIMEOUT_BACKSTOP_MS = 3_000L
    }
}
