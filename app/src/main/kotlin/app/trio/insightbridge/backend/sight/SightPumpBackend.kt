/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.backend.sight

import app.aaps.pump.insight.app_layer.AppLayerMessage
import app.aaps.pump.insight.app_layer.Service
import app.aaps.pump.insight.app_layer.history.HistoryReadingDirection
import app.aaps.pump.insight.app_layer.history.ReadHistoryEventsMessage
import app.aaps.pump.insight.app_layer.history.StartReadingHistoryMessage
import app.aaps.pump.insight.app_layer.history.StopReadingHistoryMessage
import app.aaps.pump.insight.app_layer.history.history_events.HistoryEvent
import app.aaps.pump.insight.app_layer.parameter_blocks.ActiveBRProfileBlock
import app.aaps.pump.insight.app_layer.parameter_blocks.BRProfile1Block
import app.aaps.pump.insight.app_layer.parameter_blocks.FactoryMinBasalAmountBlock
import app.aaps.pump.insight.app_layer.parameter_blocks.FactoryMinBolusAmountBlock
import app.aaps.pump.insight.app_layer.parameter_blocks.MaxBasalAmountBlock
import app.aaps.pump.insight.app_layer.parameter_blocks.MaxBolusAmountBlock
import app.aaps.pump.insight.app_layer.remote_control.CancelBolusMessage
import app.aaps.pump.insight.app_layer.remote_control.CancelTBRMessage
import app.aaps.pump.insight.app_layer.remote_control.ChangeTBRMessage
import app.aaps.pump.insight.app_layer.remote_control.ConfirmAlertMessage
import app.aaps.pump.insight.app_layer.remote_control.DeliverBolusMessage
import app.aaps.pump.insight.app_layer.remote_control.SetDateTimeMessage
import app.aaps.pump.insight.app_layer.remote_control.SetOperatingModeMessage
import app.aaps.pump.insight.app_layer.remote_control.SetTBRMessage
import app.aaps.pump.insight.app_layer.remote_control.SnoozeAlertMessage
import app.aaps.pump.insight.app_layer.status.GetActiveAlertMessage
import app.aaps.pump.insight.app_layer.status.GetActiveBasalRateMessage
import app.aaps.pump.insight.app_layer.status.GetActiveBolusesMessage
import app.aaps.pump.insight.app_layer.status.GetActiveTBRMessage
import app.aaps.pump.insight.app_layer.status.GetBatteryStatusMessage
import app.aaps.pump.insight.app_layer.status.GetCartridgeStatusMessage
import app.aaps.pump.insight.app_layer.status.GetDateTimeMessage
import app.aaps.pump.insight.app_layer.status.GetOperatingModeMessage
import app.aaps.pump.insight.app_layer.status.GetPumpStatusRegisterMessage
import app.aaps.pump.insight.app_layer.status.ResetPumpStatusRegisterMessage
import app.aaps.pump.insight.connection_service.InsightConnection
import app.aaps.pump.insight.connection_service.MessageRequest
import app.aaps.pump.insight.descriptors.ActiveBasalRate
import app.aaps.pump.insight.descriptors.ActiveBolus
import app.aaps.pump.insight.descriptors.ActiveTBR
import app.aaps.pump.insight.descriptors.Alert
import app.aaps.pump.insight.descriptors.BasalProfile
import app.aaps.pump.insight.descriptors.BasalProfileBlock
import app.aaps.pump.insight.descriptors.BatteryStatus
import app.aaps.pump.insight.descriptors.CartridgeStatus
import app.aaps.pump.insight.descriptors.CartridgeType
import app.aaps.pump.insight.descriptors.InsightState
import app.aaps.pump.insight.descriptors.OperatingMode
import app.aaps.pump.insight.descriptors.PumpTime
import app.aaps.pump.insight.exceptions.app_layer_errors.NoActiveTBRToCancelException
import app.aaps.pump.insight.exceptions.app_layer_errors.NoActiveTBRToChangeException
import app.aaps.pump.insight.exceptions.app_layer_errors.NoSuchBolusToCancelException
import app.aaps.pump.insight.exceptions.app_layer_errors.PumpAlreadyInThatStateException
import app.aaps.pump.insight.utils.ParameterBlockUtil
import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.backend.AlertCatalog
import app.trio.insightbridge.backend.BackendEvent
import app.trio.insightbridge.backend.BackendKind
import app.trio.insightbridge.backend.PumpBackend
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
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** How the pump link is held between commands. */
enum class LinkMode {

    /** Connect for commands and a periodic refresh, disconnect shortly after (saves pump battery; AndroidAPS behaviour). */
    ON_DEMAND,

    /** Hold the link permanently and reconnect with capped backoff whenever it drops. */
    ALWAYS_ON
}

/**
 * Real Accu-Chek Insight backend over the ported Sight stack ([InsightConnection]).
 *
 * Mapping of operations follows AndroidAPS `InsightPlugin`: boluses via DeliverBolusMessage with
 * progress polling, TBRs with SetTBR / ChangeTBR (ChangeTBR when one is running), cancel, operating
 * mode, basal profile written to profile 1 and activated inside ONE configuration write session
 * and read back, history read with offsets, time sync.
 *
 * Never initiates dosing on its own: every dosing call here is the direct result of a request that
 * went through [app.trio.insightbridge.engine.OpDispatcher].
 */
class SightPumpBackend(
    private val connection: InsightConnection,
    parentScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    @Volatile var linkMode: LinkMode = LinkMode.ON_DEMAND
) : PumpBackend {

    companion object {

        const val CONNECT_TIMEOUT_MS = 25_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val DOSING_TIMEOUT_MS = 30_000L
        const val ALERT_POLL_MS = 5_000L
        const val STATUS_POLL_MS = 30_000L
        const val FULL_REFRESH_MS = 5 * 60_000L
        const val BOLUS_POLL_MS = 2_000L
        const val LIMITS_MAX_AGE_MS = 10 * 60_000L
        const val MAX_PROGRAMMED_CACHE = 64
        const val MAX_EVENTS_PER_READ = 400
    }

    override val kind = BackendKind.SIGHT

    private val exceptionHandler = CoroutineExceptionHandler { _, t -> PumpLog.e("Unhandled backend coroutine failure", t) }
    private val scope = CoroutineScope(parentScope.coroutineContext + kotlinx.coroutines.SupervisorJob(parentScope.coroutineContext[Job]) + exceptionHandler)
    private val _events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<BackendEvent> = _events.asSharedFlow()

    override val linkState: StateFlow<PumpLinkState> = connection.stateFlow
        .map { linkOf(it) }
        .stateIn(scope, SharingStarted.Eagerly, linkOf(connection.state))
    override val lastContact: StateFlow<Long> = connection.lastDataTimeFlow
    override val isPaired: Boolean get() = connection.isPaired
    override val serial: String? get() = connection.pumpSystemIdentification?.serialNumber

    private fun linkOf(s: InsightState): PumpLinkState = when (s) {
        InsightState.NOT_PAIRED -> PumpLinkState.NOT_PAIRED
        InsightState.DISCONNECTED -> PumpLinkState.DISCONNECTED
        InsightState.CONNECTED -> PumpLinkState.CONNECTED
        else -> PumpLinkState.CONNECTING
    }

    // ---- cached pump state (guarded by stateLock) ----

    private class Snapshot {

        var operatingMode: OperatingMode? = null
        var battery: BatteryStatus? = null
        var cartridge: CartridgeStatus? = null
        var activeBasalRate: ActiveBasalRate? = null
        var activeTbr: ActiveTBR? = null
        var activeBoluses: List<ActiveBolus>? = null
        var activeProfile: BasalProfile? = null
        var profile1: List<BasalProfileBlock>? = null
        var pumpTime: PumpTime? = null
        var driftSeconds: Long = 0
        var alert: AlertDto? = null
        var limits: PumpLimits? = null
        var limitsAt: Long = 0
        var readAt: Long = 0
        var lastHistorySeq: Long? = null
    }

    private val stateLock = Any()
    private val snap = Snapshot()
    private var lastStatus: StatusDto? = null
    private var registerBaselineLoaded = false
    private val programmed = object : LinkedHashMap<Int, Double>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Double>?) = size > MAX_PROGRAMMED_CACHE
    }
    private val refreshMutex = Mutex()
    private val historyMutex = Mutex()
    private var monitorJob: Job? = null
    private var bolusMonitor: Job? = null
    private val keepAliveLock = Any()
    private var keepAliveHeld = false

    private val stateCallback = object : InsightConnection.StateCallback {
        override fun onStateChanged(state: InsightState?) {
            if (state == InsightState.CONNECTED) synchronized(stateLock) { registerBaselineLoaded = false }
            if (state != null) _events.tryEmit(BackendEvent.LinkChanged(linkOf(state)))
            if (state == InsightState.NOT_PAIRED) synchronized(stateLock) {
                lastStatus = null
            }
        }
    }

    init {
        connection.registerStateCallback(stateCallback)
    }

    /** Starts background monitoring (alerts, status refresh, reconnect while [LinkMode.ALWAYS_ON]). */
    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch { monitorLoop() }
    }

    override fun close() {
        monitorJob?.cancel()
        bolusMonitor?.cancel()
        connection.unregisterStateCallback(stateCallback)
        synchronized(keepAliveLock) { if (keepAliveHeld) connection.withdrawConnectionRequest(keepAliveLock); keepAliveHeld = false }
        scope.coroutineContext[Job]?.cancel()
    }

    // ------------------------------------------------------------------ link leases

    private fun applyKeepAlive() {
        synchronized(keepAliveLock) {
            val want = linkMode == LinkMode.ALWAYS_ON && connection.isPaired
            if (want && !keepAliveHeld) {
                connection.requestConnection(keepAliveLock)
                keepAliveHeld = true
            } else if (!want && keepAliveHeld) {
                connection.withdrawConnectionRequest(keepAliveLock)
                keepAliveHeld = false
            }
        }
    }

    /** Runs [block] with the pump link held and CONNECTED; releases the lease afterwards. */
    private suspend fun <T> withLink(connectTimeoutMs: Long = CONNECT_TIMEOUT_MS, block: suspend () -> T): T {
        if (!connection.isPaired) throw PumpOpException(ErrorCode.NOT_PAIRED, "The bridge has no pump pairing")
        val lease = Any()
        connection.requestConnection(lease)
        try {
            try {
                withTimeout(connectTimeoutMs) { connection.stateFlow.first { it === InsightState.CONNECTED } }
            } catch (e: TimeoutCancellationException) {
                throw PumpOpException(ErrorCode.PUMP_UNREACHABLE, "Could not connect to the pump")
            }
            return block()
        } finally {
            connection.withdrawConnectionRequest(lease)
        }
    }

    /** Awaits a request off the main threads, mapping failures. [mayHaveEffect] = command that changes pump state. */
    private suspend fun <T : AppLayerMessage> await(
        message: T, mayHaveEffect: Boolean, timeoutMs: Long = if (mayHaveEffect) DOSING_TIMEOUT_MS else REQUEST_TIMEOUT_MS
    ): T {
        val request = connection.requestMessage(message)
        return awaitRequest(request, mayHaveEffect, timeoutMs)
    }

    private suspend fun <T : AppLayerMessage> awaitRequest(request: MessageRequest<T>, mayHaveEffect: Boolean, timeoutMs: Long): T =
        withContext(Dispatchers.IO) {
            try {
                request.await(timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SightMapping.mapFailure(e, request.transmitted, mayHaveEffect)
            }
        }

    private suspend fun <T> readBlock(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PumpOpException) {
        throw e
    } catch (e: Exception) {
        throw SightMapping.mapFailure(e, false, false)
    }

    // ------------------------------------------------------------------ status

    override fun cachedStatus(): StatusDto? = synchronized(stateLock) { lastStatus }

    override suspend fun getStatus(): StatusDto {
        if (!connection.isPaired) return buildStatus(true)
        return try {
            withLink(15_000) { refreshStatus(full = true) }
            synchronized(stateLock) { lastStatus!! }
        } catch (e: PumpOpException) {
            // Unreachable: answer with what we know (the timestamp tells how old it is).
            synchronized(stateLock) { lastStatus }?.copy(pumpLink = linkState.value.wire) ?: throw e
        }
    }

    private fun buildStatus(notPaired: Boolean = false): StatusDto = synchronized(stateLock) {
        val now = Instant.ofEpochMilli(clock())
        if (notPaired) {
            return StatusDto(
                timestamp = null, pumpLink = PumpLinkState.NOT_PAIRED.wire, serial = null, firmware = null, operatingMode = null,
                battery = null, reservoir = null, basal = null, tbr = null, boluses = null, pumpTime = null, limits = null,
                activeAlert = null, lastHistorySequence = null
            )
        }
        val s = snap
        val tbr = s.activeTbr?.let {
            val elapsed = (it.initialDuration - it.remainingDuration).coerceAtLeast(0)
            TbrDto(it.percentage, it.initialDuration, it.remainingDuration, SightMapping.iso(now.minus(elapsed.toLong(), ChronoUnit.MINUTES)))
        }
        val limits = s.limits
        StatusDto(
            timestamp = if (s.readAt == 0L) null else SightMapping.iso(Instant.ofEpochMilli(s.readAt)),
            pumpLink = linkOf(connection.state).wire,
            serial = serial,
            firmware = connection.pumpFirmwareVersions?.releaseSWVersion,
            operatingMode = SightMapping.modeWire(s.operatingMode),
            battery = s.battery?.let { BatteryDto(it.batteryAmount.takeIf { p -> p in 0..100 }) },
            reservoir = s.cartridge?.let {
                if (it.isInserted) ReservoirDto(it.remainingAmount, when (it.cartridgeType) {
                    CartridgeType.PREFILLED -> "prefilled"
                    CartridgeType.SELF_FILLED -> "selfFilled"
                    null -> null
                }) else ReservoirDto(null, null)
            },
            basal = BasalDto(
                s.activeProfile?.let { BasalProfile.entries.indexOf(it) + 1 },
                // Scheduled profile rate at pump time (no TBR). Falls back to the pump's reported base rate.
                s.profile1?.let { blocks ->
                    val pumpNow = Instant.ofEpochMilli(clock()).plusSeconds(s.driftSeconds)
                    val local = java.time.LocalDateTime.ofInstant(pumpNow, zone)
                    scheduledRate(blocks, local.hour * 60 + local.minute)
                } ?: s.activeBasalRate?.activeBasalRate
            ),
            tbr = tbr,
            boluses = s.activeBoluses?.map {
                ActiveBolusDto(it.bolusID, SightMapping.bolusTypeWire(it.bolusType), it.initialAmount, it.remainingAmount)
            },
            pumpTime = s.pumpTime?.let { pt ->
                SightMapping.toInstant(zone, pt.year, pt.month, pt.day, pt.hour, pt.minute, pt.second)?.let { pumpLocal ->
                    PumpTimeDto(
                        SightMapping.iso(pumpLocal), SightMapping.isoLocal(zone, pumpLocal), s.driftSeconds.toInt()
                    )
                }
            },
            limits = limits?.let {
                LimitsDto(it.maxBolus.toDouble(), it.maxBasal.toDouble(), it.bolusStep.toDouble(), it.basalStep.toDouble(), it.minBasal.toDouble())
            },
            activeAlert = s.alert,
            lastHistorySequence = s.lastHistorySeq
        )
    }

    /** Port of AndroidAPS fetchStatus(): incremental via the status register once a baseline exists. */
    private suspend fun refreshStatus(full: Boolean) = refreshMutex.withLock {
        readBlock {
            val loaded = synchronized(stateLock) { registerBaselineLoaded }
            if (loaded && !full) {
                val reg = await(GetPumpStatusRegisterMessage(), false)
                await(
                    ResetPumpStatusRegisterMessage(
                        operatingModeChanged = reg.isOperatingModeChanged, batteryStatusChanged = reg.isBatteryStatusChanged,
                        cartridgeStatusChanged = reg.isCartridgeStatusChanged, totalDailyDoseChanged = reg.isTotalDailyDoseChanged,
                        activeTBRChanged = reg.isActiveTBRChanged, activeBolusesChanged = reg.isActiveBolusesChanged,
                        activeBasalRateChanged = reg.isActiveBasalRateChanged
                    ), false
                )
                if (reg.isOperatingModeChanged) updateMode()
                if (reg.isBatteryStatusChanged) updateBattery()
                if (reg.isCartridgeStatusChanged) updateCartridge()
                readRunningState(reg.isActiveBasalRateChanged, reg.isActiveTBRChanged, reg.isActiveBolusesChanged)
            } else {
                await(
                    ResetPumpStatusRegisterMessage(
                        operatingModeChanged = true, batteryStatusChanged = true, cartridgeStatusChanged = true,
                        totalDailyDoseChanged = true, activeBasalRateChanged = true, activeTBRChanged = true, activeBolusesChanged = true
                    ), false
                )
                updateMode(); updateBattery(); updateCartridge()
                readRunningState(true, true, true)
                synchronized(stateLock) { registerBaselineLoaded = true }
            }
            updateClock()
            updateAlert()
            if (full) {
                updateLimitsIfOld(force = true)
                updateProfile()
            }
            synchronized(stateLock) { snap.readAt = clock() }
            publishStatus()
        }
    }

    private suspend fun updateMode() {
        val m = await(GetOperatingModeMessage(), false).operatingMode
        synchronized(stateLock) { snap.operatingMode = m }
    }

    private suspend fun updateBattery() {
        val b = await(GetBatteryStatusMessage(), false).batteryStatus
        synchronized(stateLock) { snap.battery = b }
    }

    private suspend fun updateCartridge() {
        val c = await(GetCartridgeStatusMessage(), false).cartridgeStatus
        synchronized(stateLock) { snap.cartridge = c }
    }

    private suspend fun readRunningState(basal: Boolean, tbr: Boolean, boluses: Boolean) {
        val mode = synchronized(stateLock) { snap.operatingMode }
        if (mode != OperatingMode.STARTED) {
            synchronized(stateLock) { snap.activeBasalRate = null; snap.activeTbr = null; snap.activeBoluses = null }
            return
        }
        if (basal) await(GetActiveBasalRateMessage(), false).activeBasalRate.let { synchronized(stateLock) { snap.activeBasalRate = it } }
        if (tbr) await(GetActiveTBRMessage(), false).activeTBR.let { synchronized(stateLock) { snap.activeTbr = it } }
        if (boluses) await(GetActiveBolusesMessage(), false).activeBoluses.let { synchronized(stateLock) { snap.activeBoluses = it?.toList() } }
    }

    private suspend fun updateClock() {
        val pt = await(GetDateTimeMessage(), false).pumpTime ?: return
        val pumpLocal = SightMapping.toInstant(zone, pt.year, pt.month, pt.day, pt.hour, pt.minute, pt.second)
        synchronized(stateLock) {
            snap.pumpTime = pt
            if (pumpLocal != null) snap.driftSeconds = ChronoUnit.SECONDS.between(Instant.ofEpochMilli(clock()), pumpLocal)
        }
    }

    private suspend fun updateAlert() {
        val a: Alert? = await(GetActiveAlertMessage(), false).alert
        val dto = a?.let { SightMapping.alertDto(it) }
        val changed = synchronized(stateLock) {
            val c = snap.alert != dto
            snap.alert = dto
            c
        }
        if (changed && dto != null) _events.tryEmit(BackendEvent.AlertChanged(dto))
    }

    private suspend fun updateProfile() {
        val active = ParameterBlockUtil.readParameterBlock(connection, Service.CONFIGURATION, ActiveBRProfileBlock::class.java)?.activeBasalProfile
        val blocks = if (active == BasalProfile.PROFILE_1) {
            ParameterBlockUtil.readParameterBlock(connection, Service.CONFIGURATION, BRProfile1Block::class.java)?.profileBlocks?.toList()
        } else null
        synchronized(stateLock) { snap.activeProfile = active; snap.profile1 = blocks }
    }

    /** Scheduled (profile) rate at pump-local [minuteOfDay]; null if the profile is unknown or incomplete. */
    internal fun scheduledRate(blocks: List<BasalProfileBlock>?, minuteOfDay: Int): Double? {
        if (blocks.isNullOrEmpty()) return null
        var start = 0
        for (b in blocks) {
            if (minuteOfDay < start + b.duration) return b.basalAmount
            start += b.duration
        }
        return null
    }

    private suspend fun updateLimitsIfOld(force: Boolean = false) {
        val age = clock() - synchronized(stateLock) { snap.limitsAt }
        if (!force && age < LIMITS_MAX_AGE_MS) return
        val c = connection
        val maxBolus = ParameterBlockUtil.readParameterBlock(c, Service.CONFIGURATION, MaxBolusAmountBlock::class.java)?.amountLimitation
        val minBasal = ParameterBlockUtil.readParameterBlock(c, Service.CONFIGURATION, FactoryMinBasalAmountBlock::class.java)?.amountLimitation
        val maxBasal = ParameterBlockUtil.readParameterBlock(c, Service.CONFIGURATION, MaxBasalAmountBlock::class.java)?.amountLimitation
        // FactoryMinBolus is read only to confirm the block is supported; the bolus step is the fixed 0.05 U of the Insight.
        ParameterBlockUtil.readParameterBlock(c, Service.CONFIGURATION, FactoryMinBolusAmountBlock::class.java)
        if (maxBolus == null || minBasal == null || maxBasal == null) {
            throw PumpOpException(ErrorCode.INTERNAL, "The pump did not report its limits")
        }
        synchronized(stateLock) {
            snap.limits = PumpLimits(maxBolus = bd(maxBolus), maxBasal = bd(maxBasal), minBasal = bd(minBasal))
            snap.limitsAt = clock()
        }
    }

    private fun bd(v: Double): BigDecimal = BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP)

    private fun publishStatus() {
        val s = buildStatus()
        synchronized(stateLock) { lastStatus = s }
        _events.tryEmit(BackendEvent.StatusChanged(s))
    }

    override suspend fun limits(): PumpLimits {
        synchronized(stateLock) {
            val l = snap.limits
            if (l != null && clock() - snap.limitsAt < LIMITS_MAX_AGE_MS) return l
        }
        return withLink { readBlock { updateLimitsIfOld(force = true) }; synchronized(stateLock) { snap.limits!! } }
    }

    // ------------------------------------------------------------------ monitoring

    private suspend fun monitorLoop() {
        var lastAlert = 0L
        var lastStatusPoll = 0L
        var lastFull = 0L
        while (scope.isActive) {
            try {
                applyKeepAlive()
                val now = clock()
                if (connection.isPaired) {
                    if (linkMode == LinkMode.ALWAYS_ON) {
                        if (connection.state === InsightState.CONNECTED) {
                            if (now - lastFull >= FULL_REFRESH_MS) {
                                refreshStatus(full = true); lastFull = now; lastStatusPoll = now; lastAlert = now
                            } else if (now - lastStatusPoll >= STATUS_POLL_MS) {
                                refreshStatus(full = false); lastStatusPoll = now; lastAlert = now
                            } else if (now - lastAlert >= ALERT_POLL_MS) {
                                updateAlertSafe(); lastAlert = now
                            }
                        }
                    } else if (now - lastFull >= FULL_REFRESH_MS) {
                        // periodic refresh: connect, read, release (disconnects after the delay)
                        try {
                            withLink { refreshStatus(full = true) }
                        } catch (e: PumpOpException) {
                            PumpLog.i("Periodic refresh failed: ${e.code.wire}")
                        }
                        lastFull = clock()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PumpLog.w("Monitor iteration failed: ${e.javaClass.simpleName}")
            }
            delay(1_000)
        }
    }

    private suspend fun updateAlertSafe() {
        try {
            readBlock { updateAlert() }
        } catch (e: PumpOpException) {
            PumpLog.d("alert poll failed: ${e.code.wire}")
        }
    }

    // ------------------------------------------------------------------ dosing

    private suspend fun requireStarted() {
        val mode = await(GetOperatingModeMessage(), false).operatingMode
        synchronized(stateLock) { snap.operatingMode = mode }
        if (mode != OperatingMode.STARTED) {
            throw PumpOpException(ErrorCode.PUMP_SUSPENDED, "The pump is not in the started operating mode")
        }
    }

    override suspend fun deliverBolus(amount: BigDecimal): Int = withLink {
        requireStarted()
        val message = DeliverBolusMessage().apply {
            bolusType = app.aaps.pump.insight.descriptors.BolusType.STANDARD
            duration = 0
            extendedAmount = 0.0
            immediateAmount = amount.toDouble()
            disableVibration = false
        }
        val bolusId = await(message, mayHaveEffect = true).bolusId
        synchronized(stateLock) { programmed[bolusId] = amount.toDouble() }
        startBolusMonitor(bolusId, amount.toDouble())
        bolusId
    }

    /** Keeps a link lease and polls the pump until the bolus is finished, emitting progress events. */
    private fun startBolusMonitor(bolusId: Int, programmedAmount: Double) {
        bolusMonitor?.cancel()
        bolusMonitor = scope.launch {
            val lease = Any()
            connection.requestConnection(lease)
            try {
                val deadline = clock() + 30 * 60_000L
                var misses = 0
                var lastDelivered = 0.0
                while (isActive && clock() < deadline) {
                    delay(BOLUS_POLL_MS)
                    try {
                        val list = await(GetActiveBolusesMessage(), false).activeBoluses
                        val b = list?.firstOrNull { it.bolusID == bolusId }
                        if (b != null) {
                            misses = 0
                            lastDelivered = b.initialAmount - b.remainingAmount
                            _events.tryEmit(BackendEvent.BolusProgress(BolusProgressDto(bolusId, round2(lastDelivered), round2(b.remainingAmount))))
                        } else if (++misses >= 2) {
                            _events.tryEmit(BackendEvent.BolusProgress(BolusProgressDto(bolusId, round2(maxOf(lastDelivered, 0.0)), 0.0)))
                            break
                        }
                    } catch (e: PumpOpException) {
                        if (++misses >= 5) break
                    }
                }
                try { withLinkAlreadyHeld { refreshStatus(full = false) } } catch (_: Exception) {}
            } finally {
                connection.withdrawConnectionRequest(lease)
            }
        }
    }

    private suspend fun <T> withLinkAlreadyHeld(block: suspend () -> T): T = block()

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0

    override suspend fun cancelBolus(bolusId: Int): Double? = withLink {
        val before = try {
            await(GetActiveBolusesMessage(), false).activeBoluses?.firstOrNull { it.bolusID == bolusId }
        } catch (e: PumpOpException) {
            null
        }
        try {
            await(CancelBolusMessage().apply { bolusID = bolusId }, mayHaveEffect = true)
        } catch (e: PumpOpException) {
            if (e.cause is NoSuchBolusToCancelException) return@withLink null
            throw e
        }
        bolusMonitor?.cancel()
        // Delivered amount as last observed before the cancel (a lower bound); the history has the exact value.
        before?.let { round2(it.initialAmount - it.remainingAmount) }
    }

    override suspend fun setTbr(percent: Int, duration: Int) = withLink {
        requireStarted()
        val running = await(GetActiveTBRMessage(), false).activeTBR != null
        suspend fun set() { await(SetTBRMessage().apply { this.percentage = percent; this.duration = duration }, true) }
        suspend fun change() { await(ChangeTBRMessage().apply { this.percentage = percent; this.duration = duration }, true) }
        if (running) {
            try {
                change()
            } catch (e: PumpOpException) {
                // The TBR ended between the check and the command: the pump refused (certain), so set a new one.
                if (e.cause is NoActiveTBRToChangeException) set() else throw e
            }
        } else set()
        afterChange()
    }

    override suspend fun cancelTbr() = withLink {
        try {
            await(CancelTBRMessage(), true)
        } catch (e: PumpOpException) {
            if (e.cause !is NoActiveTBRToCancelException) throw e
        }
        afterChange()
    }

    override suspend fun setOperatingMode(started: Boolean) = withLink {
        try {
            await(SetOperatingModeMessage().apply { operatingMode = if (started) OperatingMode.STARTED else OperatingMode.STOPPED }, true)
        } catch (e: PumpOpException) {
            if (e.cause !is PumpAlreadyInThatStateException) throw e
        }
        afterChange()
    }

    /** Best effort status refresh after a state change; failures never turn a successful command into an error. */
    private suspend fun afterChange() {
        try {
            refreshStatus(full = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PumpLog.d("post-command refresh failed")
        }
    }

    override suspend fun setBasalProfile(segments: List<BasalSegment>): Int = withLink {
        val blocks = segments.map { s -> BasalProfileBlock().also { it.duration = s.durationMinutes; it.basalAmount = s.rate.toDouble() } }
        val profile = BRProfile1Block().also { it.profileBlocks = blocks.toMutableList() }
        val active = ActiveBRProfileBlock().also { it.activeBasalProfile = BasalProfile.PROFILE_1 }
        val session = ParameterBlockUtil.requestConfigurationBlocks(connection, profile, active)
        try {
            withContext(Dispatchers.IO) { session.await(DOSING_TIMEOUT_MS) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SightMapping.mapFailure(e, session.transmitted, true)
        }
        // Read back and compare; any difference is verifyFailed.
        val readBack = try {
            readBlock {
                val p = ParameterBlockUtil.readParameterBlock(connection, Service.CONFIGURATION, BRProfile1Block::class.java)?.profileBlocks
                val a = ParameterBlockUtil.readParameterBlock(connection, Service.CONFIGURATION, ActiveBRProfileBlock::class.java)?.activeBasalProfile
                p to a
            }
        } catch (e: PumpOpException) {
            throw PumpOpException(ErrorCode.PUMP_UNREACHABLE, "Could not read the profile back to verify it", uncertain = true, cause = e)
        }
        if (readBack.second != BasalProfile.PROFILE_1 || !profilesEqual(segments, readBack.first)) {
            throw PumpOpException(ErrorCode.VERIFY_FAILED, "The profile read back from the pump differs from the one written")
        }
        synchronized(stateLock) { snap.activeProfile = BasalProfile.PROFILE_1 }
        afterChange()
        1
    }

    /** Compares the 24 hourly rates of the requested and the read-back profile. */
    internal fun profilesEqual(requested: List<BasalSegment>, actual: List<BasalProfileBlock>?): Boolean {
        if (actual == null) return false
        val want = expandHourly(requested.map { it.durationMinutes to it.rate.toDouble() }) ?: return false
        val got = expandHourly(actual.map { it.duration to it.basalAmount }) ?: return false
        return want.indices.all { Math.abs(want[it] - got[it]) < 0.0051 }
    }

    private fun expandHourly(blocks: List<Pair<Int, Double>>): DoubleArray? {
        val out = DoubleArray(24)
        var minute = 0
        for ((dur, rate) in blocks) {
            if (dur <= 0 || dur % 60 != 0) return null
            repeat(dur / 60) { if (minute / 60 < 24) out[minute / 60] = rate; minute += 60 }
        }
        return if (minute == 1440) out else null
    }

    override suspend fun setDateTime(utc: Instant, tzOffsetMinutes: Int) = withLink {
        val local = utc.plusSeconds(tzOffsetMinutes * 60L).atZone(java.time.ZoneOffset.UTC)
        val message = SetDateTimeMessage().apply {
            pumpTime = PumpTime().also {
                it.year = local.year; it.month = local.monthValue; it.day = local.dayOfMonth
                it.hour = local.hour; it.minute = local.minute; it.second = local.second
            }
        }
        await(message, mayHaveEffect = true)
        afterChange()
    }

    // ------------------------------------------------------------------ history

    override suspend fun readHistory(sinceSequence: Long?, max: Int): HistoryPage = withLink {
        historyMutex.withLock { readBlock { readHistoryLocked(sinceSequence, max) } }
    }

    private suspend fun readHistoryLocked(since: Long?, max: Int): HistoryPage {
        updateClock()
        val drift = synchronized(stateLock) { snap.driftSeconds }
        val collected = ArrayList<HistoryEvent>()
        var more = false
        try {
            val start = StartReadingHistoryMessage()
            if (since == null) {
                start.direction = HistoryReadingDirection.BACKWARD
                start.offset = 0xFFFFFFFFL
                await(start, false)
                collected.addAll(await(ReadHistoryEventsMessage(), false).historyEvents)
            } else {
                start.direction = HistoryReadingDirection.FORWARD
                start.offset = since + 1
                await(start, false)
                while (collected.size < MAX_EVENTS_PER_READ) {
                    val batch = await(ReadHistoryEventsMessage(), false).historyEvents
                    if (batch.isEmpty()) break
                    collected.addAll(batch)
                    if (collected.size >= max) { more = true; break }
                }
            }
        } finally {
            try {
                await(StopReadingHistoryMessage(), false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        val sorted = collected.sortedBy { it.eventPosition }.distinctBy { it.eventPosition }
        val chosen = if (since == null) sorted.takeLast(max) else sorted.take(max)
        if (since != null && sorted.size > max) more = true
        val mapped = chosen.mapNotNull { e ->
            SightMapping.historyEvent(
                e, zone, drift,
                programmedLookup = { id -> synchronized(stateLock) { programmed[id] } },
                rememberProgrammed = { id, amount -> synchronized(stateLock) { programmed[id] = amount } }
            )
        }
        val last = mapped.lastOrNull()?.seq ?: sorted.lastOrNull()?.eventPosition ?: since ?: 0L
        synchronized(stateLock) { snap.lastHistorySeq = maxOf(snap.lastHistorySeq ?: 0L, sorted.lastOrNull()?.eventPosition ?: 0L) }
        return HistoryPage(mapped, last, more)
    }

    // ------------------------------------------------------------------ alerts

    private suspend fun requireActiveAlert(alertId: Int, alertType: String): Alert {
        val alert = await(GetActiveAlertMessage(), false).alert
        if (alert == null || alert.alertId != alertId || alert.alertType?.name != alertType) {
            throw PumpOpException(ErrorCode.INVALID_ARGS, "No such active alert on the pump")
        }
        return alert
    }

    override suspend fun confirmAlert(alertId: Int, alertType: String) = withLink {
        requireActiveAlert(alertId, alertType)
        await(ConfirmAlertMessage().apply { alertID = alertId }, mayHaveEffect = false)
        updateAlertSafe()
        publishStatus()
    }

    override suspend fun snoozeAlert(alertId: Int, alertType: String) = withLink {
        requireActiveAlert(alertId, alertType)
        await(SnoozeAlertMessage().apply { alertID = alertId }, mayHaveEffect = false)
        updateAlertSafe()
        publishStatus()
    }
}
