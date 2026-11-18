/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import app.trio.insightbridge.protocol.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Final result of an idempotent command, as stored and replayed. */
data class StoredOutcome(
    val ok: Boolean,
    val result: JsonObject?,
    val errorCode: ErrorCode?,
    val message: String?,
    val uncertain: Boolean
)

/** Small durable text file abstraction (atomic write). */
interface TextFile {

    fun read(): String?
    fun write(text: String)
}

class MemoryTextFile : TextFile {

    @Volatile var text: String? = null
    override fun read() = text
    override fun write(text: String) { this.text = text }
}

/**
 * PROTOCOL.md "Idempotency": the outcome of the last [maxEntries] requestIds is kept for [ttlMillis]
 * and persisted. A repeated requestId returns the stored outcome and never touches the pump.
 *
 * A command is recorded as IN_FLIGHT *before* it is sent to the pump. If the process dies in
 * between, the entry is turned into an `uncertain` failure on the next start, so a retry with the
 * same requestId can never double-dose.
 */
class IdempotencyStore(
    private val file: TextFile,
    private val clock: () -> Long,
    private val maxEntries: Int = 64,
    private val ttlMillis: Long = 24L * 3600 * 1000
) {

    sealed interface Begin {

        data object Proceed : Begin
        data class Replay(val outcome: StoredOutcome) : Begin
        data object Mismatch : Begin
        /** Same requestId is currently executing in this process; await [deferred]. */
        data class InFlight(val deferred: CompletableDeferred<StoredOutcome>) : Begin
    }

    private class Entry(
        val requestId: String,
        val op: String,
        val fingerprint: String,
        var outcome: StoredOutcome?,
        val at: Long,
        var completedAt: Long = 0
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val deferreds = HashMap<String, CompletableDeferred<StoredOutcome>>()

    val size: Int @Synchronized get() = entries.size

    init {
        load()
    }

    @Synchronized
    private fun load() {
        val text = try { file.read() } catch (_: Exception) { null } ?: return
        try {
            val arr = Json.parseToJsonElement(text) as JsonArray
            for (el in arr) {
                val o = el as JsonObject
                val outcomeEl = o["outcome"]
                val outcome = if (outcomeEl is JsonObject) decodeOutcome(outcomeEl) else interruptedOutcome()
                val e = Entry(
                    (o["requestId"] as JsonPrimitive).content,
                    (o["op"] as JsonPrimitive).content,
                    (o["fp"] as JsonPrimitive).content,
                    outcome,
                    (o["at"] as JsonPrimitive).content.toLong(),
                    (o["done"] as? JsonPrimitive)?.content?.toLongOrNull() ?: if (outcomeEl is JsonObject) 0L else clock()
                )
                entries[e.requestId] = e
            }
        } catch (_: Exception) {
            // A corrupt file must not stop the bridge; start empty. (Dosing safety then relies on the phone.)
            entries.clear()
        }
        prune()
        persist() // persist the IN_FLIGHT -> uncertain conversion
    }

    private fun interruptedOutcome() = StoredOutcome(
        ok = false, result = null, errorCode = ErrorCode.INTERNAL,
        message = "The bridge restarted while this command was in flight; the pump may or may not have executed it",
        uncertain = true
    )

    private fun prune() {
        val now = clock()
        entries.values.removeAll { now - it.at > ttlMillis && deferreds[it.requestId] == null }
        while (entries.size > maxEntries) {
            val victim = entries.values.firstOrNull { it.outcome != null } ?: break
            entries.remove(victim.requestId)
        }
    }

    enum class State(val wire: String) {
        UNKNOWN("unknown"), IN_PROGRESS("inProgress"), SUCCEEDED("succeeded"), FAILED("failed"), UNCERTAIN("uncertain")
    }

    data class Lookup(val state: State, val outcome: StoredOutcome?, val completedAt: Long?)

    /** PROTOCOL.md v2 getRequestOutcome. Never touches the pump. */
    @Synchronized
    fun lookup(requestId: String): Lookup {
        prune()
        val e = entries[requestId] ?: return Lookup(State.UNKNOWN, null, null)
        val o = e.outcome ?: return Lookup(State.IN_PROGRESS, null, null)
        val state = when {
            o.ok -> State.SUCCEEDED
            o.uncertain -> State.UNCERTAIN
            else -> State.FAILED
        }
        return Lookup(state, o, e.completedAt.takeIf { it > 0 })
    }

    @Synchronized
    fun begin(requestId: String, op: String, fingerprint: String): Begin {
        prune()
        val existing = entries[requestId]
        if (existing != null) {
            if (existing.op != op || existing.fingerprint != fingerprint) return Begin.Mismatch
            existing.outcome?.let { return Begin.Replay(it) }
            deferreds[requestId]?.let { return Begin.InFlight(it) }
            return Begin.Replay(interruptedOutcome())
        }
        entries[requestId] = Entry(requestId, op, fingerprint, null, clock())
        deferreds[requestId] = CompletableDeferred()
        try {
            persist()
        } catch (e: Exception) {
            // Not recorded durably => must not be executed.
            entries.remove(requestId)
            deferreds.remove(requestId)
            throw e
        }
        prune()
        return Begin.Proceed
    }

    @Synchronized
    fun complete(requestId: String, outcome: StoredOutcome) {
        val e = entries[requestId] ?: return
        e.outcome = outcome
        e.completedAt = clock()
        persist()
        deferreds.remove(requestId)?.complete(outcome)
        prune()
    }

    private fun persist() {
        val arr = JsonArray(entries.values.map { e ->
            buildJsonObject {
                put("requestId", e.requestId)
                put("op", e.op)
                put("fp", e.fingerprint)
                put("at", e.at)
                put("done", e.completedAt)
                e.outcome?.let { put("outcome", encodeOutcome(it)) }
            }
        })
        try {
            file.write(arr.toString())
        } catch (e: Exception) {
            // Cannot persist: surface loudly rather than silently weakening the guarantee.
            throw IllegalStateException("idempotency store not writable", e)
        }
    }

    private fun encodeOutcome(o: StoredOutcome) = buildJsonObject {
        put("ok", o.ok)
        put("result", o.result ?: JsonNull)
        put("code", o.errorCode?.wire?.let { JsonPrimitive(it) } ?: JsonNull)
        put("message", o.message?.let { JsonPrimitive(it) } ?: JsonNull)
        put("uncertain", o.uncertain)
    }

    private fun decodeOutcome(o: JsonObject) = StoredOutcome(
        ok = (o["ok"] as JsonPrimitive).content.toBoolean(),
        result = o["result"] as? JsonObject,
        errorCode = (o["code"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { ErrorCode.fromWire(it) },
        message = (o["message"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        uncertain = (o["uncertain"] as JsonPrimitive).content.toBoolean()
    )
}
