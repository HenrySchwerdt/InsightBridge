/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge

import android.app.Application
import android.content.Context
import app.aaps.pump.insight.connection_service.InsightConnection
import app.aaps.pump.insight.utils.PairingDataStorage
import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.backend.PumpBackend
import app.trio.insightbridge.backend.SimulatedPump
import app.trio.insightbridge.backend.sight.LinkMode
import app.trio.insightbridge.backend.sight.SightPumpBackend
import app.trio.insightbridge.engine.IdempotencyStore
import app.trio.insightbridge.engine.PairingController
import app.trio.insightbridge.protocol.PumpOpException
import app.trio.insightbridge.service.HealthSnapshot
import app.trio.insightbridge.service.Notifications
import app.trio.insightbridge.service.Perms
import app.trio.insightbridge.store.AtomicTextFile
import app.trio.insightbridge.store.EncryptedFileKeyValueStore
import app.trio.insightbridge.store.KeystoreBlobCipher
import app.trio.insightbridge.store.KvIdentityStore
import app.trio.insightbridge.store.SettingsStore
import app.trio.insightbridge.util.FileLog
import app.trio.insightbridge.util.LogBuffer
import app.trio.insightbridge.util.LoggingExceptionHandler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide singletons. All persistent state lives in device protected storage so the service
 * can run in Direct Boot (before the first unlock); secrets are wrapped by an Android Keystore key.
 * Activities only observe flows from here; the foreground service owns the lifetime.
 */
class BridgeRuntime(val app: Application) {

    val clock: () -> Long = System::currentTimeMillis
    val deContext: Context = app.createDeviceProtectedStorageContext()
    private val dir = File(deContext.filesDir, "bridge").also { it.mkdirs() }

    val log = LogBuffer()
    val crashLog = FileLog(File(dir, "crash.log"))
    val settings = SettingsStore(AtomicTextFile(File(dir, "settings.json")))
    val identity = KvIdentityStore(EncryptedFileKeyValueStore(File(dir, "identity.bin"), KeystoreBlobCipher("insightbridge_identity_v1")))
    val pairingData = PairingDataStorage(EncryptedFileKeyValueStore(File(dir, "pump.bin"), KeystoreBlobCipher("insightbridge_pump_v1")))
    val idempotency by lazy { IdempotencyStore(AtomicTextFile(File(dir, "idempotency.json")), clock) }
    val pairing = PairingController(clock)
    val notifications by lazy { Notifications(app) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + LoggingExceptionHandler)

    val connection: InsightConnection by lazy { InsightConnection(app, pairingData, { Perms.bluetoothGranted(app) }) }

    private val _backend = MutableStateFlow<PumpBackend?>(null)
    val backend: StateFlow<PumpBackend?> = _backend.asStateFlow()
    private val _conflict = MutableStateFlow(false)
    val simulatorConflict: StateFlow<Boolean> = _conflict.asStateFlow()
    private val _health = MutableStateFlow(HealthSnapshot())
    val health: StateFlow<HealthSnapshot> = _health.asStateFlow()

    /** Last status known to the bridge (updated by backend events); null until first read. */
    val status = MutableStateFlow<app.trio.insightbridge.protocol.StatusDto?>(null)

    /** Set by the running [app.trio.insightbridge.service.BridgeCore]; drops the connected iPhone. */
    @Volatile var disconnectPhoneHook: (() -> Unit)? = null

    /** Debug builds only: lets the developer screen preview wizard states (UI only, no pairing happens). */
    @Volatile var debugPumpFlow: app.trio.insightbridge.service.PumpPairingFlow? = null

    private var sight: SightPumpBackend? = null
    private var simulator: SimulatedPump? = null

    val pumpPaired: Boolean get() = pairingData.paired
    val phonePaired: Boolean get() = identity.ltk() != null

    init {
        PumpLog.sink = log
        // The simulator is never resumed by itself after a restart: it has to be switched on (and confirmed) again.
        if (settings.clearStaleSimulatorFlag()) {
            PumpLog.w("Simulator was on before the restart; switched off, it needs to be enabled again")
        }
        log.persistent = { crashLog.append(it) }
    }

    /**
     * Chooses the backend. The simulator is never a silent fallback: it only runs when explicitly
     * enabled, and is refused (conflict, no backend at all) while a real pump is paired.
     */
    @Synchronized
    fun refreshBackend() {
        val wantSim = settings.simulatorEnabled
        if (wantSim && pumpPaired) {
            _conflict.value = true
            retire(sight); sight = null
            retire(simulator); simulator = null
            _backend.value = null
            PumpLog.e("Simulator enabled while a real pump is paired: refusing to run any backend")
            return
        }
        _conflict.value = false
        if (wantSim) {
            retire(sight); sight = null
            val sim = simulator ?: SimulatedPump(clock).also { simulator = it }
            if (_backend.value !== sim) _backend.value = sim
        } else {
            retire(simulator); simulator = null
            val s = sight ?: SightPumpBackend(
                connection, scope, clock,
                linkMode = if (settings.alwaysOnLink) LinkMode.ALWAYS_ON else LinkMode.ON_DEMAND
            ).also { it.start(); sight = it }
            if (_backend.value !== s) _backend.value = s
        }
    }

    private fun retire(b: PumpBackend?) {
        try { b?.close() } catch (t: Throwable) { PumpLog.w("backend close", t) }
    }

    fun simulator(): SimulatedPump? = simulator

    /** Developer simulator: only while no real pump is paired. Returns false if refused. */
    fun enableSimulator(): Boolean {
        if (pumpPaired) return false
        settings.simulatorEnabled = true
        refreshBackend()
        return true
    }

    fun disableSimulator() {
        settings.simulatorEnabled = false
        refreshBackend()
    }

    fun setAlwaysOn(enabled: Boolean) {
        settings.alwaysOnLink = enabled
        sight?.linkMode = if (enabled) LinkMode.ALWAYS_ON else LinkMode.ON_DEMAND
    }

    /** Forgets the paired iPhone (the user must pair again) and drops its connection. */
    fun unpairPhone() {
        identity.clearPairing()
        pairing.cancel()
        disconnectPhoneHook?.invoke()
    }

    /** Forgets the pump pairing and (if the simulator ran) leaves simulator mode. */
    fun unpairPump() {
        connection.reset()
        status.value = null
        refreshBackend()
    }

    /** Pull-to-refresh: reads the pump now (falls back to the last known status). */
    suspend fun refreshStatusNow(): Boolean {
        val b = backend.value ?: return false
        return try {
            status.value = b.getStatus()
            true
        } catch (e: PumpOpException) {
            false
        }
    }

    fun stopBackends() {
        retire(sight); sight = null
        retire(simulator); simulator = null
        _backend.value = null
    }

    fun publishHealth(h: HealthSnapshot) {
        _health.value = h
    }

    /** Explicit user tap on a pump alert action (the bridge never confirms alerts by itself). */
    suspend fun userConfirmAlert(alertId: Int, alertType: String): Boolean = userAlertAction { it.confirmAlert(alertId, alertType) }
    suspend fun userSnoozeAlert(alertId: Int, alertType: String): Boolean = userAlertAction { it.snoozeAlert(alertId, alertType) }

    private suspend fun userAlertAction(block: suspend (PumpBackend) -> Unit): Boolean {
        val b = backend.value ?: return false
        return try {
            block(b)
            true
        } catch (e: PumpOpException) {
            PumpLog.w("Alert action failed: ${e.code.wire}")
            false
        }
    }
}
