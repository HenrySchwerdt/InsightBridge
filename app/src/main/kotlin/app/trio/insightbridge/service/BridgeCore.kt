/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.BridgeRuntime
import app.trio.insightbridge.BuildConfig
import app.trio.insightbridge.backend.BackendEvent
import app.trio.insightbridge.backend.PumpBackend
import app.trio.insightbridge.ble.GattServerManager
import app.trio.insightbridge.engine.BridgeEngine
import app.trio.insightbridge.engine.LinkEventListener
import app.trio.insightbridge.engine.LinkStats
import app.trio.insightbridge.engine.EventMapper
import app.trio.insightbridge.engine.OpDispatcher
import app.trio.insightbridge.protocol.PumpLinkState
import app.trio.insightbridge.protocol.Wire
import app.trio.insightbridge.util.LoggingExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wires BLE <-> engine <-> backend and runs the periodic loops (1 s tick, 30 s health). Owned by
 * [BridgeService]. Every loop iteration is exception-proof: a failure is logged and the loop goes on.
 */
class BridgeCore(private val rt: BridgeRuntime) : GattServerManager.Listener, LinkEventListener {

    private var scope: CoroutineScope? = null
    private var gatt: GattServerManager? = null
    private var engine: BridgeEngine? = null
    private val outage = PumpOutagePolicy()
    @Volatile private var lastPhoneRequest = 0L
    @Volatile private var startedAt = 0L
    private var lastStatusBroadcast = 0L
    private var lastAlertKey: String? = null
    private var heartbeat: (Long) -> Unit = {}
    private var wakeLock: WakeLockHolder? = null
    private val stats = LinkStats(rt.clock, { rt.settings.get(it) }, { k, v -> rt.settings.put(k, v) })
    private var lastLoggedAdvertising: Boolean? = null

    val isRunning: Boolean get() = scope != null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(onHeartbeat: (Long) -> Unit) {
        if (scope != null) return
        heartbeat = onHeartbeat
        startedAt = rt.clock()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default + LoggingExceptionHandler)
        scope = s
        rt.refreshBackend()
        rt.disconnectPhoneHook = { disconnectPhone() }
        rt.restartBleHook = { reason -> restartBle(reason) }
        val g = GattServerManager(rt.app, { Perms.bluetoothGranted(rt.app) }, rt.identity.bridgeId.joinToString("") { "%02x".format(it) }, this)
        gatt = g
        val wl = WakeLockHolder(rt.app)
        wakeLock = wl
        val dispatcher = OpDispatcher(
            backend = { rt.backend.value },
            store = rt.idempotency,
            clock = rt.clock,
            bridgeVersion = BuildConfig.VERSION_NAME,
            bluetoothOn = { Perms.bluetoothOn(rt.app) },
            onRequest = { lastPhoneRequest = rt.clock() }
        )
        val e = BridgeEngine(
            s, rt.clock, rt.identity, rt.pairing, dispatcher, g,
            onPhoneActivity = { lastPhoneRequest = rt.clock() },
            linkEvents = this,
            onRequestStart = { wl.requestStarted() },
            onRequestEnd = { wl.requestFinished() }
        )
        engine = e
        g.start()

        s.launch { tickLoop(e) }
        s.launch { healthLoop() }
        s.launch {
            rt.backend.flatMapLatest { it?.events ?: flowOf() }.collectLatest { event -> forward(e, event) }
        }
        PumpLog.i("Bridge core started (v${BuildConfig.VERSION_NAME})")
    }

    fun stop() {
        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
        rt.disconnectPhoneHook = null
        rt.restartBleHook = null
        try { gatt?.stop() } catch (t: Throwable) { PumpLog.w("gatt stop", t) }
        gatt = null
        wakeLock?.releaseAll()
        wakeLock = null
        engine = null
        rt.stopBackends()
        rt.publishHealth(rt.health.value.copy(serviceRunning = false))
    }

    fun onBluetoothState(on: Boolean) {
        val g = gatt ?: return
        PumpLog.i("Bluetooth adapter ${if (on) "on" else "off"}")
        if (on) g.start() else g.stop()
        publishHealth()
    }

    /** User-triggered recovery ("restart the bridge"): rebuilds the GATT server and advertising and drops the iPhone link. */
    fun restartBle(reason: String) {
        PumpLog.i("BLE restart by user: $reason")
        gatt?.restart(reason)
        publishHealth()
    }

    private suspend fun tickLoop(e: BridgeEngine) {
        var n = 0
        while (currentScopeActive()) {
            try {
                val now = rt.clock()
                heartbeat(now)
                e.tick()
                gatt?.let { g ->
                    // Advertising mode follows the session; supervision restarts advertising / the server when needed.
                    g.setSessionActive(e.phoneState.value == app.trio.insightbridge.engine.PhoneLinkState.SESSION)
                    g.supervise()
                    val adv = g.advertising.value
                    if (lastLoggedAdvertising != adv) {
                        if (lastLoggedAdvertising != null) PumpLog.i("Advertising state: ${if (adv) "on" else "OFF"}")
                        lastLoggedAdvertising = adv
                    }
                }
                (rt.backend.value as? app.trio.insightbridge.backend.SimulatedPump)?.tick()
                // status event at least every 5 minutes while the phone is connected
                if (now - lastStatusBroadcast >= 5 * 60_000L) {
                    rt.backend.value?.cachedStatus()?.let { e.broadcastEvent(Wire.statusEvent(it)); lastStatusBroadcast = now }
                }
                publishHealth()
                if (++n % 30 == 0) rt.notifications.updateStatus(rt.health.value)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                PumpLog.e("Tick failed", t)
            }
            delay(1_000)
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // guarded by Perms.bluetoothGranted below
    private suspend fun healthLoop() {
        // One-time hint if an earlier build renamed the system Bluetooth adapter (this build never does).
        try {
            val name = (rt.app.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.name
            if (Perms.bluetoothGranted(rt.app) && name?.startsWith("InsightBridge-") == true && !rt.settings.getBoolean("btNameHintShown")) {
                rt.settings.putBoolean("btNameHintShown", true)
                rt.notifications.adapterRenamedHint()
            }
        } catch (_: Throwable) {
        }
        if (rt.settings.crashPending) {
            rt.settings.crashPending = false
            rt.notifications.restartedAfterCrash()
        }
        while (currentScopeActive()) {
            try {
                val b = rt.backend.value
                val now = rt.clock()
                when (outage.evaluate(now, b?.lastContact?.value ?: 0, startedAt, b != null && b.isPaired && b.kind == app.trio.insightbridge.backend.BackendKind.SIGHT)) {
                    PumpOutagePolicy.Action.NOTIFY -> rt.notifications.pumpUnreachable()
                    PumpOutagePolicy.Action.CLEAR -> rt.notifications.clearPumpUnreachable()
                    PumpOutagePolicy.Action.NONE -> Unit
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                PumpLog.e("Health loop failed", t)
            }
            delay(30_000)
        }
    }

    private fun currentScopeActive() = scope?.isActive == true

    private fun forward(e: BridgeEngine, event: BackendEvent) {
        val (wire, throttle) = EventMapper.toWire(event)
        e.broadcastEvent(wire, throttle)
        when (event) {
            is BackendEvent.LinkChanged -> PumpLog.i("Pump link: ${event.state.wire}")
            is BackendEvent.StatusChanged -> {
                rt.status.value = event.status
                lastStatusBroadcast = rt.clock()
                if (event.status.activeAlert == null && lastAlertKey != null) { lastAlertKey = null; rt.notifications.clearAlert() }
            }
            is BackendEvent.AlertChanged -> {
                rt.status.value = rt.status.value?.copy(activeAlert = event.alert)
                val key = "${event.alert.alertId}:${event.alert.alertType}:${event.alert.status}"
                if (key != lastAlertKey) { lastAlertKey = key; rt.notifications.alert(event.alert) }
            }
            else -> Unit
        }
    }

    private fun publishHealth() {
        val b: PumpBackend? = rt.backend.value
        rt.publishHealth(
            HealthSnapshot(
                now = rt.clock(),
                serviceStartedAt = startedAt,
                serviceRunning = true,
                bluetoothOn = Perms.bluetoothOn(rt.app),
                serverOpen = gatt?.serverOpen?.value ?: false,
                advertising = gatt?.advertising?.value ?: false,
                pumpLink = b?.linkState?.value ?: PumpLinkState.NOT_PAIRED,
                lastPumpContact = b?.lastContact?.value ?: 0,
                phoneLink = engine?.phoneState?.value ?: app.trio.insightbridge.engine.PhoneLinkState.NONE,
                lastPhoneRequest = lastPhoneRequest,
                simulated = b?.kind == app.trio.insightbridge.backend.BackendKind.SIMULATED,
                simulatorConflict = rt.simulatorConflict.value,
                pumpPaired = rt.pumpPaired,
                phonePaired = rt.phonePaired,
                permissionsOk = Perms.bluetoothGranted(rt.app),
                notificationsOk = Perms.notificationsPermitted(rt.app),
                batteryExempt = Perms.batteryExempt(rt.app),
                sessions24h = stats.sessionsLast24h(),
                lastDisconnectAt = stats.lastDisconnect()?.at ?: 0,
                lastDisconnectReason = stats.lastDisconnect()?.reason,
                gattConnections = gatt?.connectedCount() ?: 0,
                advertiseError = gatt?.lastAdvertiseError ?: 0
            )
        )
    }

    // ---- GattServerManager.Listener ----

    override fun onConnected(centralId: String, generation: Long) {
        wakeLock?.touch()
        engine?.onCentralConnected(centralId, generation)
    }

    override fun onDisconnected(centralId: String, generation: Long, reason: String) {
        wakeLock?.touch()
        engine?.onCentralDisconnected(centralId, generation, reason)
    }

    override fun onFragment(centralId: String, generation: Long, bytes: ByteArray) {
        wakeLock?.touch()
        engine?.onFragment(centralId, bytes, generation)
    }

    // ---- LinkEventListener (called under the engine lock: keep it quick) ----

    override fun onSessionEstablished(centralId: String, preempted: Int) {
        stats.sessionEstablished()
    }

    override fun onLinkClosed(centralId: String, reason: String, wasSession: Boolean, durationMs: Long) {
        // Links that never got past the handshake are noise for "why did my session end": only count real ones,
        // but keep the last reason of any link when no session was ever recorded.
        if (wasSession || stats.lastDisconnect() == null) stats.linkClosed(reason, wasSession)
    }

    /** For the pairing UI. */
    fun disconnectPhone() { engine?.disconnectPhone() }
}
