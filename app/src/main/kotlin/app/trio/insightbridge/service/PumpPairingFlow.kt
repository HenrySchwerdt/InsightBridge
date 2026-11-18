/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.aaps.pump.insight.connection_service.InsightConnection
import app.aaps.pump.insight.descriptors.InsightState
import app.aaps.pump.insight.exceptions.ConnectionFailedException
import app.aaps.pump.insight.exceptions.TimeoutException
import app.aaps.pump.insight.exceptions.satl_errors.SatlPairingRejectedException
import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.BridgeRuntime
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredPump(val name: String, val address: String)

/** Why pump pairing failed (the UI maps these to troubleshooting text). */
enum class PumpPairFailure { REJECTED, TIMEOUT, CONNECTION_FAILED, BLUETOOTH_UNAVAILABLE, OTHER, SIMULATOR_ACTIVE }

sealed interface PumpPairState {

    data object Idle : PumpPairState
    data class Scanning(val devices: List<DiscoveredPump>) : PumpPairState
    data class Connecting(val address: String) : PumpPairState
    /** The 10-character code shown here must match the one on the pump display. */
    data class ShowCode(val code: String) : PumpPairState
    data object Finishing : PumpPairState
    data object Done : PumpPairState
    data class Failed(val reason: PumpPairFailure) : PumpPairState
}

/**
 * Drives the Sight pairing with the pump (Bluetooth Classic discovery -> select -> verification
 * code compare). Wraps the ported [InsightConnection] pairing protocol.
 */
@SuppressLint("MissingPermission") // permissions are requested by the wizard before this runs
class PumpPairingFlow(private val rt: BridgeRuntime) {

    private val ctx: Context = rt.app
    private val _state = MutableStateFlow<PumpPairState>(PumpPairState.Idle)
    val state: StateFlow<PumpPairState> = _state.asStateFlow()

    private val lock = Any()
    private val found = LinkedHashMap<String, DiscoveredPump>()
    private var receiver: BroadcastReceiver? = null
    private var callback: InsightConnection.StateCallback? = null
    private var exceptionCallback: InsightConnection.ExceptionCallback? = null
    private var held = false

    private fun adapter(): BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun startScan() {
        cancelInternal(resetState = false)
        val a = adapter()
        if (a == null || !a.isEnabled || !Perms.bluetoothGranted(ctx)) {
            _state.value = PumpPairState.Failed(PumpPairFailure.BLUETOOTH_UNAVAILABLE)
            return
        }
        found.clear()
        _state.value = PumpPairState.Scanning(emptyList())
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    if (intent.action == BluetoothDevice.ACTION_FOUND) {
                        val d = if (android.os.Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                        val name = d.name ?: return
                        synchronized(lock) {
                            if (found.size < 50) found[d.address] = DiscoveredPump(name, d.address)
                            if (_state.value is PumpPairState.Scanning) _state.value = PumpPairState.Scanning(found.values.toList())
                        }
                    } else if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                        // Discovery ends after ~12 s; keep listing and scan again while the user is on this screen.
                        if (_state.value is PumpPairState.Scanning) try { adapter()?.startDiscovery() } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    PumpLog.e("discovery receiver", t)
                }
            }
        }
        receiver = r
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply { addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        androidx.core.content.ContextCompat.registerReceiver(ctx, r, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        try { a.startDiscovery() } catch (t: Throwable) { PumpLog.w("startDiscovery", t) }
    }

    fun select(address: String) {
        stopScan()
        // The simulator and a real pump are mutually exclusive: never pair while the simulator is on.
        if (rt.settings.simulatorEnabled) {
            _state.value = PumpPairState.Failed(PumpPairFailure.SIMULATOR_ACTIVE)
            return
        }
        val conn = rt.connection
        if (conn.isPaired) conn.reset()
        _state.value = PumpPairState.Connecting(address)
        val cb = object : InsightConnection.StateCallback {
            override fun onStateChanged(state: InsightState?) {
                when (state) {
                    InsightState.AWAITING_CODE_CONFIRMATION -> conn.verificationString?.let { _state.value = PumpPairState.ShowCode(it) }
                    InsightState.NOT_PAIRED, InsightState.DISCONNECTED -> Unit
                    else -> Unit
                }
            }

            override fun onPumpPaired() {
                _state.value = PumpPairState.Done
                release()
                // Not inline: this callback runs under the connection's monitor, refreshBackend takes the
                // runtime lock and (indirectly) the connection monitor again - lock order inversion.
                rt.scope.launch { rt.refreshBackend() }
            }
        }
        val ex = object : InsightConnection.ExceptionCallback {
            override fun onExceptionOccur(e: Exception?) {
                if (_state.value is PumpPairState.Done) return
                _state.value = PumpPairState.Failed(
                    when (e) {
                        is SatlPairingRejectedException -> PumpPairFailure.REJECTED
                        is TimeoutException -> PumpPairFailure.TIMEOUT
                        is ConnectionFailedException -> PumpPairFailure.CONNECTION_FAILED
                        else -> PumpPairFailure.OTHER
                    }
                )
                release()
            }
        }
        callback = cb
        exceptionCallback = ex
        conn.registerStateCallback(cb)
        conn.registerExceptionCallback(ex)
        held = true
        conn.requestConnection(this)
        try {
            conn.pair(address)
        } catch (t: Throwable) {
            PumpLog.e("pair() failed", t)
            _state.value = PumpPairState.Failed(PumpPairFailure.OTHER)
            release()
        }
    }

    fun confirmCode() {
        _state.value = PumpPairState.Finishing
        rt.connection.confirmVerificationString()
    }

    fun rejectCode() {
        rt.connection.rejectVerificationString()
    }

    fun stopScan() {
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Throwable) {} }
        receiver = null
        try { adapter()?.cancelDiscovery() } catch (_: Throwable) {}
    }

    private fun release() {
        val conn = rt.connection
        callback?.let { conn.unregisterStateCallback(it) }
        exceptionCallback?.let { conn.unregisterExceptionCallback(it) }
        callback = null
        exceptionCallback = null
        if (held) { held = false; conn.withdrawConnectionRequest(this) }
    }

    private fun cancelInternal(resetState: Boolean) {
        stopScan()
        release()
        if (resetState) _state.value = PumpPairState.Idle
    }

    /** DEBUG builds only: shows the code-compare screen with a fake code (UI preview, nothing is paired). */
    fun debugShowCode(code: String) {
        if (app.trio.insightbridge.BuildConfig.DEBUG) _state.value = PumpPairState.ShowCode(code)
    }

    /** Aborts any running pairing step and returns to idle. */
    fun cancel() = cancelInternal(true)
}
