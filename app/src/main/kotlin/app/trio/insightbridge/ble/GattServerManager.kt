/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.engine.LinkTransmitter
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** GATT layout, PROTOCOL.md section 1. */
object GattIds {

    val SERVICE: UUID = UUID.fromString("2462D0E9-9BA1-4434-8DFE-75D844AC9945")
    val RX: UUID = UUID.fromString("6FAFD9DD-5BCE-4A1D-B262-A61B50AE7842")
    val TX: UUID = UUID.fromString("7EDC9323-9BFA-4A7E-AFBB-92DEE0F0B917")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * BLE peripheral side: GATT server with rx (write) and tx (notify), advertising, per-central
 * bounded notification queue and MTU tracking. Self-healing: [ensureHealthy] (called periodically by
 * the service) recreates the server / restarts advertising; [stop]/[start] are used when the
 * Bluetooth adapter toggles.
 *
 * Every platform callback is wrapped so no exception can escape into the Bluetooth thread.
 */
@SuppressLint("MissingPermission") // runtime permissions are checked via [permitted] before any call
class GattServerManager(
    private val context: Context,
    private val permitted: () -> Boolean,
    private val bridgeIdHex: String,
    private val listener: Listener
) : LinkTransmitter {

    interface Listener {

        fun onConnected(centralId: String)
        fun onDisconnected(centralId: String)
        fun onFragment(centralId: String, bytes: ByteArray)
    }

    companion object {

        const val MAX_QUEUE_PER_CENTRAL = 512
        const val NOTIFY_STUCK_MS = 6_000L
        const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
    }

    private class Central(val device: BluetoothDevice) {

        var mtu = DEFAULT_MTU
        var subscribed = false
        val queue = ArrayDeque<ByteArray>()
        var sending = false
        var sendingSince = 0L
    }

    private val lock = Any()
    private val notifier = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "gatt-notifier").apply { isDaemon = true } }
    private val centrals = HashMap<String, Central>()
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var serviceAdded = false
    private var advertiseCallback: AdvertiseCallback? = null
    @Volatile private var wanted = false

    private val _advertising = MutableStateFlow(false)
    val advertising: StateFlow<Boolean> = _advertising.asStateFlow()
    private val _serverOpen = MutableStateFlow(false)
    val serverOpen: StateFlow<Boolean> = _serverOpen.asStateFlow()
    private var advertiseFailures = 0
    @Volatile var lastAdvertiseError: Int = 0
        private set

    val localName: String = "InsightBridge-" + bridgeIdHex.takeLast(4).uppercase()

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        wanted = true
        synchronized(lock) { startLocked() }
    }

    fun stop() {
        wanted = false
        synchronized(lock) { stopLocked() }
    }

    /** Creates the server if missing and (re)starts advertising if it stopped. Safe to call often. */
    fun ensureHealthy() {
        if (!wanted) return
        synchronized(lock) {
            val adapter = adapter()
            if (adapter == null || !adapter.isEnabled || !permitted()) {
                if (server != null) stopLocked()
                return
            }
            if (server == null || !serviceAdded) {
                stopLocked()
                startLocked()
                return
            }
            if (!_advertising.value) startAdvertisingLocked()
            // Notification stuck? The callback never came: drop that central.
            val now = System.currentTimeMillis()
            centrals.values.filter { it.sending && now - it.sendingSince > NOTIFY_STUCK_MS }.toList().forEach {
                PumpLog.w("Notification stalled, disconnecting central")
                disconnectLocked(it.device)
            }
        }
    }

    private fun startLocked() {
        try {
            val adapter = adapter()
            if (adapter == null || !adapter.isEnabled || !permitted()) return
            if (server != null) return
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val s = manager.openGattServer(context, callback) ?: run {
                PumpLog.w("openGattServer returned null")
                return
            }
            server = s
            _serverOpen.value = true
            serviceAdded = false
            val service = BluetoothGattService(GattIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val rx = BluetoothGattCharacteristic(
                GattIds.RX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val tx = BluetoothGattCharacteristic(
                GattIds.TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ
            )
            tx.addDescriptor(
                BluetoothGattDescriptor(
                    GattIds.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
            service.addCharacteristic(rx)
            service.addCharacteristic(tx)
            txChar = tx
            if (!s.addService(service)) {
                PumpLog.w("addService failed")
                stopLocked()
            }
        } catch (t: Throwable) {
            PumpLog.e("GATT server start failed", t)
            stopLocked()
        }
    }

    private fun stopLocked() {
        try {
            stopAdvertisingLocked()
            val connected = centrals.keys.toList()
            server?.let {
                try { it.clearServices() } catch (_: Throwable) {}
                try { it.close() } catch (_: Throwable) {}
            }
            server = null
            txChar = null
            serviceAdded = false
            _serverOpen.value = false
            centrals.clear()
            connected.forEach { id -> notifier.execute { safe { listener.onDisconnected(id) } } }
        } catch (t: Throwable) {
            PumpLog.e("GATT server stop failed", t)
        }
    }

    // ------------------------------------------------------------------ advertising

    private fun startAdvertisingLocked() {
        try {
            val adapter = adapter() ?: return
            if (!adapter.isEnabled || !permitted()) return
            stopAdvertisingLocked()
            // The scan response carries the local name; it comes from the adapter name.
            try {
                if (adapter.name != localName) adapter.name = localName
            } catch (t: Throwable) {
                PumpLog.w("Could not set advertised name", t)
            }
            val adv = adapter.bluetoothLeAdvertiser ?: run {
                PumpLog.w("No BLE advertiser available")
                return
            }
            advertiser = adv
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(GattIds.SERVICE))
                .build()
            val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
            val cb = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    safe {
                        advertiseFailures = 0
                        lastAdvertiseError = 0
                        _advertising.value = true
                        PumpLog.i("Advertising as $localName")
                    }
                }

                override fun onStartFailure(errorCode: Int) {
                    safe {
                        advertiseFailures++
                        lastAdvertiseError = errorCode
                        _advertising.value = false
                        // ensureHealthy() retries on its next tick (backoff = tick interval).
                        PumpLog.w("Advertising failed, code $errorCode (attempt $advertiseFailures)")
                    }
                }
            }
            advertiseCallback = cb
            adv.startAdvertising(settings, data, scan, cb)
        } catch (t: Throwable) {
            PumpLog.e("startAdvertising failed", t)
            _advertising.value = false
        }
    }

    private fun stopAdvertisingLocked() {
        try {
            advertiseCallback?.let { advertiser?.stopAdvertising(it) }
        } catch (t: Throwable) {
            PumpLog.w("stopAdvertising", t)
        }
        advertiseCallback = null
        _advertising.value = false
    }

    // ------------------------------------------------------------------ GATT callbacks

    private val callback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) = safe {
            synchronized(lock) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    serviceAdded = true
                    startAdvertisingLocked()
                } else {
                    PumpLog.w("Service add failed: $status")
                    stopLocked()
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = safe {
            val id = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(lock) { centrals[id] = Central(device) }
                listener.onConnected(id)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val had = synchronized(lock) { centrals.remove(id) != null }
                if (had) listener.onDisconnected(id)
                // Some stacks stop advertising while connected: make sure it is back after a disconnect.
                synchronized(lock) { if (wanted && server != null && !_advertising.value) startAdvertisingLocked() }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = safe {
            synchronized(lock) { centrals[device.address]?.mtu = mtu.coerceAtLeast(DEFAULT_MTU) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) = safe {
            val ok = characteristic.uuid == GattIds.RX && value != null && !preparedWrite && offset == 0
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null
                )
            }
            if (ok) listener.onFragment(device.address, value!!.copyOf())
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) = safe {
            var status = BluetoothGatt.GATT_SUCCESS
            if (descriptor.uuid == GattIds.CCCD && value != null) {
                val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                synchronized(lock) { centrals[device.address]?.subscribed = enable }
            } else status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            if (responseNeeded) server?.sendResponse(device, requestId, status, 0, null)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) = safe {
            val subscribed = synchronized(lock) { centrals[device.address]?.subscribed == true }
            server?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            )
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = safe {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = safe {
            synchronized(lock) {
                val c = centrals[device.address] ?: return@synchronized
                c.sending = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    PumpLog.w("Notification failed: $status")
                    disconnectLocked(device)
                } else pumpLocked(c)
            }
        }
    }

    // ------------------------------------------------------------------ LinkTransmitter

    override fun send(centralId: String, fragments: List<ByteArray>): Boolean = synchronized(lock) {
        val c = centrals[centralId] ?: return false
        if (!c.subscribed) return false
        if (c.queue.size + fragments.size > MAX_QUEUE_PER_CENTRAL) return false
        fragments.forEach { c.queue.addLast(it) }
        pumpLocked(c)
        true
    }

    override fun maxFragmentSize(centralId: String): Int = synchronized(lock) {
        ((centrals[centralId]?.mtu ?: DEFAULT_MTU) - ATT_OVERHEAD).coerceAtLeast(20)
    }

    override fun disconnect(centralId: String) {
        synchronized(lock) { centrals[centralId]?.let { disconnectLocked(it.device) } }
    }

    private fun disconnectLocked(device: BluetoothDevice) {
        try {
            server?.cancelConnection(device)
        } catch (t: Throwable) {
            PumpLog.w("cancelConnection", t)
        }
        val removed = centrals.remove(device.address)
        // Not inline: the engine may be calling us under its own lock (lock order engine -> gatt).
        if (removed != null) notifier.execute { safe { listener.onDisconnected(device.address) } }
    }

    @SuppressLint("WrongConstant") // pre-33 notify path; lint confuses the deprecated setValue overload
    private fun pumpLocked(c: Central) {
        if (c.sending) return
        val next = c.queue.removeFirstOrNull() ?: return
        val s = server ?: return
        val ch = txChar ?: return
        c.sending = true
        c.sendingSince = System.currentTimeMillis()
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(c.device, ch, false, next) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = next
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(c.device, ch, false)
            }
        } catch (t: Throwable) {
            PumpLog.w("notify threw", t)
            false
        }
        if (!ok) {
            c.sending = false
            disconnectLocked(c.device)
        }
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            PumpLog.e("GATT callback failure", t)
        }
    }
}
