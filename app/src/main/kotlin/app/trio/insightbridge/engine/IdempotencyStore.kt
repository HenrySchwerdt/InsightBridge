/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

import app.trio.insightbridge.protocol.ErrorCode
import app.aaps.pump.insight.utils.PumpLog
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

    /** Content, or null if the file does not exist. Throws [java.io.IOException] if it exists but cannot be read (transient). */
    fun read(): String?

    fun write(text: String)

    /** Moves an unusable (corrupt) file aside, e.g. to `<name>.bak`, so it is kept for diagnosis and never overwritten. */
    fun quarantine() {}
}

class MemoryTextFile : TextFile {

    @Volatile var text: String? = null
    @Volatile var backup: String? = null
    /** Test hook: number of upcoming reads that fail with an IOException. */
    @Volatile var failReads = 0
    override fun read(): String? {
        if (failReads > 0) { failReads--; throw java.io.IOException("simulated read failure") }
        return text
    }
    override fun write(text: String) { this.text = text }
    override fun quarantine() { backup = text; text = null }
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
    private val maxEntries: Int = 5000,
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
    private var loaded = false
    private var dirty = false

    /** How many corrupt journals were moved aside (diagnostics; the UI/log can surface it). */
    @Volatile var quarantinedCount = 0
        private set

    val size: Int @Synchronized get() = entries.size

    init {
        tryLoad()
    }

    /**
     * Loads the journal. Absent = empty. Unreadable (transient I/O) = retry, then stay unloaded: dosing is
     * refused ([begin] throws) until a later attempt succeeds, and the file is NEVER overwritten.
     * Unparseable (corrupt) = moved aside to a backup and started empty (loudly logged).
     */
    @Synchronized
    private fun tryLoad(): Boolean {
        if (loaded) return true
        var text: String? = null
        var ok = false
        for (attempt in 1..3) {
            try {
                text = file.read(); ok = true; break
            } catch (e: Exception) {
                PumpLog.w("idempotency journal unreadable (attempt $attempt)", e)
            }
        }
        if (!ok) return false
        if (text != null) {
            try {
                parse(text)
            } catch (e: Exception) {
                PumpLog.e("idempotency journal is corrupt; moved aside and starting empty", e)
                entries.clear()
                try { file.quarantine() } catch (q: Exception) { PumpLog.e("could not quarantine journal", q); return false }
                quarantinedCount++
            }
        }
        loaded = true
        prune()
        try { persist() } catch (e: Exception) { dirty = true } // persist the IN_FLIGHT -> uncertain conversion
        return true
    }

    private fun parse(text: String) {
        val arr = Json.parseToJsonElement(text) as JsonArray
        val parsed = ArrayList<Entry>()
        for (el in arr) {
            val o = el as JsonObject
            val outcomeEl = o["outcome"]
            val outcome = if (outcomeEl is JsonObject) decodeOutcome(outcomeEl) else interruptedOutcome()
            parsed.add(
                Entry(
                    (o["requestId"] as JsonPrimitive).content,
                    (o["op"] as JsonPrimitive).content,
                    (o["fp"] as JsonPrimitive).content,
                    outcome,
                    (o["at"] as JsonPrimitive).content.toLong(),
                    (o["done"] as? JsonPrimitive)?.content?.toLongOrNull() ?: if (outcomeEl is JsonObject) 0L else clock()
                )
            )
        }
        entries.clear()
        parsed.forEach { entries[it.requestId] = it }
    }

    private fun interruptedOutcome() = StoredOutcome(
        ok = false, result = null, errorCode = ErrorCode.INTERNAL,
        message = "The bridge restarted while this command was in flight; the pump may or may not have executed it",
        uncertain = true
    )

    /** TTL first (24 h), then the oldest completed entries beyond [maxEntries]. In-flight entries are never evicted. */
    private fun prune() {
        val now = clock()
        entries.values.removeAll { now - it.at > ttlMillis && deferreds[it.requestId] == null }
        if (entries.size > maxEntries) {
            val it = entries.values.iterator()
            while (it.hasNext() && entries.size > maxEntries) {
                if (it.next().outcome != null) it.remove()
            }
        }
    }

    enum class State(val wire: String) {
        UNKNOWN("unknown"), IN_PROGRESS("inProgress"), SUCCEEDED("succeeded"), FAILED("failed"), UNCERTAIN("uncertain")
    }

    data class Lookup(val state: State, val outcome: StoredOutcome?, val completedAt: Long?)

    /** PROTOCOL.md v2 getRequestOutcome. Never touches the pump. Throws if the journal is unreadable. */
    @Synchronized
    fun lookup(requestId: String): Lookup {
        check(tryLoad()) { "idempotency journal unavailable" }
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

    /**
     * Records the command as in-flight BEFORE it is sent anywhere. Throws [IllegalStateException] if the
     * journal cannot be read or written: a command that is not recorded durably must not be executed.
     */
    @Synchronized
    fun begin(requestId: String, op: String, fingerprint: String): Begin {
        check(tryLoad()) { "idempotency journal unavailable" }
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
            throw IllegalStateException("idempotency journal not writable", e)
        }
        prune()
        return Begin.Proceed
    }

    /**
     * Stores the final outcome. Never throws: the deferred is completed first, and a failing write is
     * logged and retried by the next write, so the caller always reports the REAL outcome of a command
     * that already ran.
     */
    @Synchronized
    fun complete(requestId: String, outcome: StoredOutcome) {
        val e = entries[requestId]
        if (e == null) {
            deferreds.remove(requestId)?.complete(outcome)
            return
        }
        e.outcome = outcome
        e.completedAt = clock()
        deferreds.remove(requestId)?.complete(outcome)
        try {
            persist()
        } catch (ex: Exception) {
            dirty = true
            PumpLog.e("could not persist the outcome of a completed command", ex)
        }
        prune()
    }

    /**
     * A late reply (after the dispatcher already reported an uncertain outcome) replaces ONLY an uncertain outcome.
     * Returns true if it was applied.
     */
    @Synchronized
    fun amend(requestId: String, outcome: StoredOutcome): Boolean {
        val e = entries[requestId] ?: return false
        val current = e.outcome ?: return false
        if (current.ok || !current.uncertain) return false
        e.outcome = outcome
        e.completedAt = clock()
        try { persist() } catch (ex: Exception) { dirty = true; PumpLog.e("could not persist amended outcome", ex) }
        return true
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
        file.write(arr.toString())
        dirty = false
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
