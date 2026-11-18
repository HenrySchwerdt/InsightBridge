/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.backend

import app.trio.insightbridge.engine.PumpLimits
import app.trio.insightbridge.protocol.ActiveBolusDto
import app.trio.insightbridge.protocol.AlertDto
import app.trio.insightbridge.protocol.BasalDto
import app.trio.insightbridge.protocol.BasalSegment
import app.trio.insightbridge.protocol.BatteryDto
import app.trio.insightbridge.protocol.BolusProgressDto
import app.trio.insightbridge.protocol.ErrorCode
import app.trio.insightbridge.protocol.HistoryEventDto
import app.trio.insightbridge.protocol.HistoryPage
import app.trio.insightbridge.protocol.LimitsDto
import app.trio.insightbridge.protocol.PumpLinkState
import app.trio.insightbridge.protocol.PumpOpException
import app.trio.insightbridge.protocol.PumpTimeDto
import app.trio.insightbridge.protocol.ReservoirDto
import app.trio.insightbridge.protocol.StatusDto
import app.trio.insightbridge.protocol.TbrDto
import app.trio.insightbridge.protocol.WireMode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Developer simulator that models an Insight closely enough to drive the whole chain
 * Trio <-> BLE <-> bridge without a pump. NO INSULIN IS EVER DELIVERED.
 *
 * Time is injected ([clock]); the model is evaluated lazily from it, and [tick] additionally emits
 * bolus progress events. This makes it fully deterministic in JVM tests.
 *
 * Failure injection: [injectLinkDrop], [injectUncertain], [injectFailure], [injectAlert].
 */
class SimulatedPump(
    private val clock: () -> Long,
    private val bolusRateUnitsPerMinute: Double = 2.0,
    val limitsConfig: PumpLimits = PumpLimits(
        maxBolus = BigDecimal("10.0"), maxBasal = BigDecimal("2.5"), minBasal = BigDecimal("0.02")
    ),
    initialReservoir: Double = 200.0,
    private val maxHistory: Int = 2000
) : PumpBackend {

    companion object {

        const val SERIAL = "SIMULATOR"
        const val FIRMWARE = "sim"
    }

    private class SimBolus(val id: Int, val programmed: Double, val startMs: Long) {

        var delivered = 0.0
        var done = false
        var cancelled = false
    }

    private class Tbr(val percent: Int, val duration: Int, val startMs: Long)

    private class Injection(val op: String?, val kind: Kind, val executed: Boolean, val code: ErrorCode?) {

        enum class Kind { UNCERTAIN, FAIL }
    }

    private val lock = Any()
    private val _events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<BackendEvent> = _events.asSharedFlow()
    private val _link = MutableStateFlow(PumpLinkState.CONNECTED)
    override val linkState: StateFlow<PumpLinkState> = _link.asStateFlow()
    private val _lastContact = MutableStateFlow(clock())
    override val lastContact: StateFlow<Long> = _lastContact.asStateFlow()
    override val isPaired: Boolean = true
    override val serial: String = SERIAL
    override val kind = BackendKind.SIMULATED

    private var reservoir = initialReservoir
    private var battery = 100
    private var started = true
    private var profile: List<BasalSegment> = listOf(BasalSegment(1440, BigDecimal("0.80")))
    private var tbr: Tbr? = null
    private val boluses = LinkedHashMap<Int, SimBolus>()
    private var nextBolusId = 1
    private var seq = 1000L
    private val history = ArrayDeque<HistoryEventDto>()
    private var alert: AlertDto? = null
    private var nextAlertId = 1
    private var lastAdvance = clock()
    private var linkDownUntil = 0L
    private val injections = ArrayDeque<Injection>()
    private var lastStatus: StatusDto? = null
    private var timeOffsetSeconds = 0L

    // ---------- injection API (tests / developer screen) ----------

    /** Link is down for [durationMs]; every command fails with a certain pumpUnreachable. */
    fun injectLinkDrop(durationMs: Long) = synchronized(lock) {
        linkDownUntil = clock() + durationMs
        setLink(PumpLinkState.DISCONNECTED)
    }

    fun restoreLink() = synchronized(lock) {
        linkDownUntil = 0
        setLink(PumpLinkState.CONNECTED)
    }

    /**
     * The next matching dosing command ([op] = op name like "deliverBolus", or null for any) ends
     * in an uncertain pumpUnreachable. [executed] says whether the simulated pump DID apply it.
     */
    fun injectUncertain(op: String? = null, executed: Boolean) = synchronized(lock) {
        injections.add(Injection(op, Injection.Kind.UNCERTAIN, executed, null))
    }

    /** The next matching command fails with a certain (not executed) [code]. */
    fun injectFailure(op: String? = null, code: ErrorCode) = synchronized(lock) {
        injections.add(Injection(op, Injection.Kind.FAIL, false, code))
    }

    fun injectAlert(type: String, status: String = "active", tbrPercent: Int? = null, remainingInsulin: Double? = null) {
        require(type in AlertCatalog.knownTypes)
        val a = synchronized(lock) {
            val dto = AlertDto(nextAlertId++, type, AlertCatalog.categoryOf(type), status, tbrPercent, remainingInsulin)
            alert = dto
            addHistory("alert", buildJsonObject {
                put("alertId", dto.alertId); put("alertType", dto.alertType); put("category", dto.category)
            })
            dto
        }
        _events.tryEmit(BackendEvent.AlertChanged(a))
        emitStatus()
    }

    fun setBattery(percent: Int) = synchronized(lock) { battery = percent.coerceIn(0, 100) }
    fun setReservoir(units: Double) = synchronized(lock) { reservoir = units }
    fun reservoirUnits(): Double = synchronized(lock) { advance(); reservoir }
    fun currentAlert(): AlertDto? = synchronized(lock) { alert }

    // ---------- model ----------

    private fun now() = clock()
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()
    private fun local(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms + timeOffsetSeconds * 1000), ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.SECONDS).toString()

    private fun setLink(s: PumpLinkState) {
        if (_link.value != s) {
            _link.value = s
            _events.tryEmit(BackendEvent.LinkChanged(s))
        }
    }

    private fun addHistory(type: String, data: JsonObject, atMs: Long = now()): HistoryEventDto {
        val e = HistoryEventDto(++seq, iso(atMs), local(atMs), type, data)
        history.addLast(e)
        while (history.size > maxHistory) history.removeFirst()
        return e
    }

    private fun basalRateAt(ms: Long): Double {
        val minuteOfDay = ((ms / 60_000) % 1440).toInt()
        var start = 0
        for (s in profile) {
            if (minuteOfDay < start + s.durationMinutes) return s.rate.toDouble()
            start += s.durationMinutes
        }
        return profile.last().rate.toDouble()
    }

    /** Brings time-dependent state up to date: TBR expiry, bolus progress, basal consumption. */
    private fun advance() {
        val t = now()
        _lastContact.value = t
        val dt = t - lastAdvance
        if (dt <= 0) return
        if (linkDownUntil != 0L && t >= linkDownUntil) {
            linkDownUntil = 0
            setLink(PumpLinkState.CONNECTED)
        }
        // Basal consumption is integrated piecewise so a TBR that ends inside the interval is accounted for exactly.
        val activeTbr = tbr
        val tbrEnd = activeTbr?.let { it.startMs + it.duration * 60_000L }
        if (started) {
            val splitAt = if (tbrEnd != null && tbrEnd in (lastAdvance + 1)..t) tbrEnd else t
            val factor1 = (activeTbr?.percent ?: 100) / 100.0
            reservoir -= basalRateAt(lastAdvance) * factor1 * (splitAt - lastAdvance) / 3_600_000.0
            if (splitAt < t) reservoir -= basalRateAt(splitAt) * (t - splitAt) / 3_600_000.0
            reservoir = reservoir.coerceAtLeast(0.0)
        }
        if (activeTbr != null && tbrEnd != null && t >= tbrEnd) {
            tbr = null
            addHistory("tbrEnd", buildJsonObject { put("percent", activeTbr.percent); put("duration", activeTbr.duration) }, tbrEnd)
        }
        for (b in boluses.values) {
            if (b.done) continue
            val target = minOf(b.programmed, (t - b.startMs) / 60_000.0 * bolusRateUnitsPerMinute)
            val inc = (target - b.delivered).coerceAtLeast(0.0)
            b.delivered += inc
            reservoir = (reservoir - inc).coerceAtLeast(0.0)
            if (b.delivered >= b.programmed - 1e-9) finishBolus(b, t)
        }
        // keep the map bounded
        while (boluses.size > 16) boluses.remove(boluses.keys.first())
        lastAdvance = t
    }

    private fun finishBolus(b: SimBolus, t: Long) {
        b.done = true
        addHistory("bolusDelivered", buildJsonObject {
            put("bolusId", b.id); put("bolusType", "standard")
            put("programmed", b.programmed); put("delivered", round2(b.delivered))
            put("start", iso(b.startMs)); put("end", iso(t))
        }, t)
        _events.tryEmit(BackendEvent.BolusProgress(BolusProgressDto(b.id, round2(b.delivered), 0.0)))
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0

    private fun checkLink() {
        advance()
        if (linkDownUntil != 0L) throw PumpOpException(ErrorCode.PUMP_UNREACHABLE, "Simulated link drop", uncertain = false)
    }

    /** Pops a matching injection. Returns it so the caller can decide whether to apply the change. */
    private fun takeInjection(op: String): Injection? {
        val it = injections.iterator()
        while (it.hasNext()) {
            val inj = it.next()
            if (inj.op == null || inj.op == op) {
                it.remove()
                return inj
            }
        }
        return null
    }

    private fun <T> dosing(op: String, apply: () -> T): T = synchronized(lock) {
        checkLink()
        val inj = takeInjection(op)
        if (inj != null && inj.kind == Injection.Kind.FAIL) {
            throw PumpOpException(inj.code ?: ErrorCode.INTERNAL, "Simulated failure")
        }
        if (inj != null && !inj.executed) {
            throw PumpOpException(ErrorCode.PUMP_UNREACHABLE, "Simulated link loss after sending", uncertain = true)
        }
        val r = apply()
        if (inj != null) {
            emitStatusLocked()
            throw PumpOpException(ErrorCode.PUMP_UNREACHABLE, "Simulated link loss after sending", uncertain = true)
        }
        r
    }

    // ---------- PumpBackend ----------

    override suspend fun limits(): PumpLimits = limitsConfig

    override suspend fun getStatus(): StatusDto = synchronized(lock) {
        advance()
        val s = buildStatus()
        lastStatus = s
        s
    }

    override fun cachedStatus(): StatusDto? = synchronized(lock) { advance(); buildStatus().also { lastStatus = it } }

    private fun buildStatus(): StatusDto {
        val t = now()
        val activeTbr = tbr
        val mode = if (started) WireMode.STARTED else WireMode.STOPPED
        return StatusDto(
            timestamp = iso(t),
            pumpLink = if (linkDownUntil != 0L) PumpLinkState.DISCONNECTED.wire else PumpLinkState.CONNECTED.wire,
            serial = SERIAL, firmware = FIRMWARE,
            operatingMode = mode,
            battery = BatteryDto(battery),
            reservoir = ReservoirDto(round2(reservoir), "prefilled"),
            basal = BasalDto(1, basalRateAt(t + timeOffsetSeconds * 1000)),
            tbr = activeTbr?.let {
                val elapsedMin = ((t - it.startMs) / 60_000).toInt()
                TbrDto(it.percent, it.duration, (it.duration - elapsedMin).coerceAtLeast(0), iso(it.startMs))
            },
            boluses = boluses.values.filter { !it.done }.map {
                ActiveBolusDto(it.id, "standard", it.programmed, round2(it.programmed - it.delivered))
            },
            pumpTime = PumpTimeDto(iso(t + timeOffsetSeconds * 1000), local(t), timeOffsetSeconds.toInt()),
            limits = LimitsDto(
                limitsConfig.maxBolus.toDouble(), limitsConfig.maxBasal.toDouble(),
                limitsConfig.bolusStep.toDouble(), limitsConfig.basalStep.toDouble(), limitsConfig.minBasal.toDouble()
            ),
            activeAlert = alert,
            lastHistorySequence = history.lastOrNull()?.seq ?: seq
        )
    }

    private fun emitStatus() = synchronized(lock) { emitStatusLocked() }

    private fun emitStatusLocked() {
        advance()
        val s = buildStatus()
        lastStatus = s
        _events.tryEmit(BackendEvent.StatusChanged(s))
    }

    /** Advances the model and emits bolus progress; the owner calls this about once a second. */
    fun tick() {
        val progress = synchronized(lock) {
            advance()
            boluses.values.filter { !it.done }
                .map { BolusProgressDto(it.id, round2(it.delivered), round2(it.programmed - it.delivered)) }
        }
        progress.forEach { _events.tryEmit(BackendEvent.BolusProgress(it)) }
    }

    override suspend fun deliverBolus(amount: BigDecimal): BolusStarted = dosing("deliverBolus") {
        if (!started) throw PumpOpException(ErrorCode.PUMP_SUSPENDED, "Pump is not running")
        if (boluses.values.any { !it.done }) throw PumpOpException(ErrorCode.PUMP_BUSY, "A bolus is already running")
        val units = amount.toDouble()
        if (units > reservoir) throw PumpOpException(ErrorCode.LIMIT_EXCEEDED, "Not enough insulin in the reservoir")
        val id = nextBolusId
        nextBolusId = if (nextBolusId >= 0xFFFF) 1 else nextBolusId + 1
        val b = SimBolus(id, units, now())
        boluses[id] = b
        addHistory("bolusProgrammed", buildJsonObject {
            put("bolusId", id); put("bolusType", "standard"); put("amount", units)
        })
        emitStatusLocked()
        BolusStarted(id, amount)
    }

    override suspend fun cancelBolus(bolusId: Int): Double? = synchronized(lock) {
        checkLink()
        advance()
        val b = boluses[bolusId]
        if (b == null || b.done) return@synchronized null
        b.done = true
        b.cancelled = true
        val delivered = round2(b.delivered)
        addHistory("bolusDelivered", buildJsonObject {
            put("bolusId", b.id); put("bolusType", "standard")
            put("programmed", b.programmed); put("delivered", delivered)
            put("start", iso(b.startMs)); put("end", iso(now()))
        })
        emitStatusLocked()
        delivered
    }

    override suspend fun setTbr(percent: Int, duration: Int) = dosing("setTBR") {
        if (!started) throw PumpOpException(ErrorCode.PUMP_SUSPENDED, "Pump is not running")
        val t = now()
        tbr?.let { old ->
            val elapsed = ((t - old.startMs) / 60_000).toInt().coerceAtMost(old.duration)
            addHistory("tbrEnd", buildJsonObject { put("percent", old.percent); put("duration", elapsed) })
        }
        tbr = Tbr(percent, duration, t)
        addHistory("tbrStart", buildJsonObject { put("percent", percent); put("duration", duration) })
        emitStatusLocked()
    }

    override suspend fun cancelTbr() = dosing("cancelTBR") {
        val old = tbr
        if (old != null) {
            val elapsed = ((now() - old.startMs) / 60_000).toInt().coerceAtMost(old.duration)
            addHistory("tbrEnd", buildJsonObject { put("percent", old.percent); put("duration", elapsed) })
            tbr = null
            emitStatusLocked()
        }
    }

    override suspend fun setOperatingMode(started: Boolean) = dosing("setOperatingMode") {
        if (this.started != started) {
            val old = if (this.started) WireMode.STARTED else WireMode.STOPPED
            this.started = started
            if (!started) {
                // running bolus is aborted by a stop, as on the real pump
                boluses.values.filter { !it.done }.forEach { finishBolus(it, now()) }
            }
            addHistory("operatingModeChanged", buildJsonObject {
                put("oldMode", old); put("newMode", if (started) WireMode.STARTED else WireMode.STOPPED)
            })
            emitStatusLocked()
        }
    }

    override suspend fun setBasalProfile(segments: List<BasalSegment>): Int = dosing("setBasalProfile") {
        profile = segments.toList()
        emitStatusLocked()
        1
    }

    override suspend fun setDateTime(utc: Instant, tzOffsetMinutes: Int) = synchronized(lock) {
        checkLink()
        val old = local(now())
        timeOffsetSeconds = tzOffsetMinutes * 60L + (utc.toEpochMilli() - now()) / 1000
        addHistory("dateTimeChanged", buildJsonObject { put("old", old); put("new", local(now())) })
        emitStatusLocked()
    }

    override suspend fun readHistory(sinceSequence: Long?, max: Int): HistoryPage = synchronized(lock) {
        checkLink()
        advance()
        val all = history.toList()
        val last = all.lastOrNull()?.seq ?: seq
        if (sinceSequence == null) {
            val newest = all.takeLast(max)
            return@synchronized HistoryPage(newest, last, false)
        }
        val newer = all.filter { it.seq > sinceSequence }
        val page = newer.take(max)
        HistoryPage(page, page.lastOrNull()?.seq ?: last, newer.size > max)
    }

    override suspend fun confirmAlert(alertId: Int, alertType: String) = synchronized(lock) {
        checkLink()
        val a = alert
        if (a == null || a.alertId != alertId || a.alertType != alertType) {
            throw PumpOpException(ErrorCode.INVALID_ARGS, "No such active alert")
        }
        alert = null
        emitStatusLocked()
    }

    override suspend fun snoozeAlert(alertId: Int, alertType: String) = synchronized(lock) {
        checkLink()
        val a = alert
        if (a == null || a.alertId != alertId || a.alertType != alertType) {
            throw PumpOpException(ErrorCode.INVALID_ARGS, "No such active alert")
        }
        alert = a.copy(status = "snoozed")
        emitStatusLocked()
    }

    override fun close() {}
}
