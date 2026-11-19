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
import android.bluetooth.BluetoothStatusCodes
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
 * BLE peripheral side: GATT server with rx (write) and tx (notify), advertising, a bounded notification queue per
 * connection and per-connection MTU.
 *
 * Self-healing without the user (PROTOCOL.md 1a): [supervise] runs once a second from the service. It applies
 * [AdvertisingPolicy] to a snapshot of the state, so advertising is restarted after every disconnect, after failures
 * (backoff capped at 5 s), when the advertising mode should change (low-latency without a session, balanced with
 * one), and the GATT server is recreated when it or its service disappeared. [start]/[stop] follow the Bluetooth
 * adapter. Every connection has its own [Central] (MTU, subscription, queue, generation); callbacks of a stale
 * connection are ignored.
 *
 * Every platform callback is wrapped so no exception can escape into the Bluetooth thread.
 */
@SuppressLint("MissingPermission") // runtime permissions are checked via [permitted] before any call
class GattServerManager(
    private val context: Context,
    private val permitted: () -> Boolean,
    private val bridgeIdHex: String,
    private val listener: Listener,
    private val clock: () -> Long = System::currentTimeMillis
) : LinkTransmitter {

    interface Listener {

        /** [generation] is unique per connection: the same device reconnecting gets a new one. */
        fun onConnected(centralId: String, generation: Long)
        fun onDisconnected(centralId: String, generation: Long, reason: String)
        fun onFragment(centralId: String, generation: Long, bytes: ByteArray)
    }

    companion object {

        const val MAX_QUEUE_PER_CENTRAL = 512
        const val NOTIFY_STUCK_MS = 6_000L
        const val DEFAULT_MTU = 23
        /** The stack answers "busy" while a previous notification is still in flight: retry shortly instead of dropping the link. */
        const val NOTIFY_RETRY_DELAY_MS = 40L
        const val NOTIFY_MAX_RETRIES = 25
        /** Some stacks stop a connectable advertisement when a central connects: refresh it shortly after. */
        const val POST_CONNECT_REFRESH_MS = 1_500L
        private const val ATT_OVERHEAD = 3
    }

    private class Central(val device: BluetoothDevice, val generation: Long) {

        var mtu = DEFAULT_MTU
        var subscribed = false
        val queue = ArrayDeque<ByteArray>()
        var sending = false
        var sendingSince = 0L
        var retries = 0
    }

    private val lock = Any()
    private val generations = java.util.concurrent.atomic.AtomicLong()
    private val advTokens = java.util.concurrent.atomic.AtomicLong()
    private val notifier = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r -> Thread(r, "gatt-notifier").apply { isDaemon = true } }
    private val centrals = HashMap<String, Central>()
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var serviceAdded = false
    private var serverOpenedAt = 0L
    private var advertiseCallback: AdvertiseCallback? = null
    @Volatile private var wanted = false
    @Volatile private var sessionActive = false

    // advertising bookkeeping (guarded by lock)
    private var advertisedMode: AdvMode? = null
    private var startPending = false
    private var lastAttemptAt = 0L
    private var lastStartSuccessAt = 0L
    private var consecutiveFailures = 0
    private var lastVerifyAt = 0L
    private var forceRefreshAt = 0L

    private val _advertising = MutableStateFlow(false)
    val advertising: StateFlow<Boolean> = _advertising.asStateFlow()
    private val _serverOpen = MutableStateFlow(false)
    val serverOpen: StateFlow<Boolean> = _serverOpen.asStateFlow()
    @Volatile var lastAdvertiseError: Int = 0
        private set

    /** Label of this bridge for logs/UI; not advertised as the Bluetooth name. */
    val localName: String = "InsightBridge-" + bridgeIdHex.takeLast(4).uppercase()

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun adapterReady(): Boolean {
        val a = adapter()
        return a != null && a.isEnabled && permitted()
    }

    /** True while the engine has an authenticated iPhone session: selects the advertising mode. */
    fun setSessionActive(active: Boolean) {
        sessionActive = active
    }

    fun connectedCount(): Int = synchronized(lock) { centrals.size }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        wanted = true
        synchronized(lock) {
            consecutiveFailures = 0
            startLocked()
        }
    }

    fun stop() {
        wanted = false
        synchronized(lock) { stopLocked("bridge stopping or Bluetooth off") }
    }

    /** Full rebuild requested by the user ("restart the bridge"): server, service and advertising from scratch. */
    fun restart(reason: String) {
        if (!wanted) return
        synchronized(lock) {
            PumpLog.i("BLE restart requested: $reason")
            stopLocked("restart: $reason")
            consecutiveFailures = 0
            lastAttemptAt = 0
            startLocked()
        }
    }

    /**
     * One supervision tick (call about once a second). Cheap when everything is fine; every 60 s it verifies that the
     * service is still registered and refreshes the advertisement when a central is connected.
     */
    fun supervise() {
        if (!wanted) return
        try {
            synchronized(lock) {
                val now = clock()
                stalledNotificationsLocked(now)
                val verify = now - lastVerifyAt >= AdvertisingPolicy.VERIFY_INTERVAL_MS
                if (verify) lastVerifyAt = now
                val forced = forceRefreshAt != 0L && now >= forceRefreshAt
                val ready = adapterReady()
                val snapshot = AdvSnapshot(
                    now = now,
                    adapterReady = ready,
                    serverOpen = server != null,
                    serviceAdded = serviceAdded,
                    serverAgeMs = now - serverOpenedAt,
                    serviceStillRegistered = if (verify && server != null && serviceAdded) serviceRegistered() else null,
                    advertising = _advertising.value,
                    advertisedMode = advertisedMode,
                    startPending = startPending,
                    desiredMode = AdvertisingPolicy.desiredMode(sessionActive),
                    consecutiveFailures = consecutiveFailures,
                    lastAttemptAt = lastAttemptAt,
                    refreshDue = forced || (verify && AdvertisingPolicy.refreshDue(centrals.size, now, lastStartSuccessAt))
                )
                if (forced) forceRefreshAt = 0
                execute(AdvertisingPolicy.decide(snapshot), now)
            }
        } catch (t: Throwable) {
            PumpLog.e("BLE supervision failed", t)
        }
    }

    private fun serviceRegistered(): Boolean = try {
        server?.getService(GattIds.SERVICE) != null
    } catch (t: Throwable) {
        PumpLog.w("getService failed", t)
        false
    }

    private fun execute(action: AdvAction, now: Long) {
        when (action) {
            AdvAction.None -> Unit
            AdvAction.StopAll -> {
                PumpLog.i("BLE stopping: Bluetooth off or permission missing")
                stopLocked("Bluetooth off or permission missing")
            }
            is AdvAction.RecreateServer -> {
                PumpLog.w("BLE recreating GATT server: ${action.reason}")
                lastAttemptAt = now
                stopLocked("server recreated: ${action.reason}")
                startLocked()
            }
            is AdvAction.StartAdvertising -> startAdvertisingLocked(action.mode, "advertising was off")
            is AdvAction.RestartAdvertising -> startAdvertisingLocked(action.mode, action.reason)
        }
    }

    /** A notification whose completion callback never came wedges that connection's queue: drop that connection. */
    private fun stalledNotificationsLocked(now: Long) {
        centrals.values.filter { it.sending && now - it.sendingSince > NOTIFY_STUCK_MS }.toList().forEach {
            PumpLog.w("BLE ${shortId(it.device.address)}#${it.generation} notification stalled for ${(now - it.sendingSince) / 1000} s, disconnecting")
            disconnectLocked(it.device, "notification stalled")
        }
    }

    private fun startLocked() {
        try {
            if (!adapterReady()) return
            if (server != null) return
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val s = manager.openGattServer(context, callback) ?: run {
                PumpLog.w("openGattServer returned null")
                consecutiveFailures++
                lastAttemptAt = clock()
                return
            }
            server = s
            serverOpenedAt = clock()
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
                consecutiveFailures++
                lastAttemptAt = clock()
                stopLocked("addService failed")
            } else {
                PumpLog.i("BLE GATT server opened, adding service")
            }
        } catch (t: Throwable) {
            PumpLog.e("GATT server start failed", t)
            consecutiveFailures++
            lastAttemptAt = clock()
            stopLocked("start failed")
        }
    }

    private fun stopLocked(reason: String) {
        try {
            val wasAdvertising = _advertising.value
            stopAdvertisingLocked()
            val connected = centrals.map { it.key to it.value.generation }
            server?.let {
                try { it.clearServices() } catch (_: Throwable) {}
                try { it.close() } catch (_: Throwable) {}
            }
            val hadServer = server != null
            server = null
            txChar = null
            serviceAdded = false
            _serverOpen.value = false
            centrals.clear()
            if (hadServer) PumpLog.i("BLE GATT server closed ($reason; advertising was ${if (wasAdvertising) "on" else "off"}, ${connected.size} connection(s) dropped)")
            connected.forEach { (id, gen) -> notifier.execute { safe { listener.onDisconnected(id, gen, "GATT server closed: $reason") } } }
        } catch (t: Throwable) {
            PumpLog.e("GATT server stop failed", t)
        }
    }

    // ------------------------------------------------------------------ advertising

    private fun startAdvertisingLocked(mode: AdvMode, reason: String) {
        val now = clock()
        try {
            val adapter = adapter() ?: return
            if (!adapter.isEnabled || !permitted()) return
            stopAdvertisingLocked()
            lastAttemptAt = now
            val adv = adapter.bluetoothLeAdvertiser ?: run {
                PumpLog.w("BLE no advertiser available")
                consecutiveFailures++
                return
            }
            advertiser = adv
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(if (mode == AdvMode.LOW_LATENCY) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(GattIds.SERVICE))
                .build()
            // The system Bluetooth name is NOT touched (renaming the phone's adapter is intrusive and persistent).
            // The central finds the bridge by the service UUID; the last 4 hex digits of the bridge id travel as
            // service data so a UI can tell two bridges apart (PROTOCOL.md's "InsightBridge-XXXX" local name is
            // therefore not advertised; the real bridgeId is learned at pairing).
            val scan = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceData(ParcelUuid(GattIds.SERVICE), bridgeIdHex.takeLast(4).uppercase().toByteArray(Charsets.US_ASCII))
                .build()
            val token = advTokens.incrementAndGet()
            val cb = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) = safe {
                    synchronized(lock) {
                        if (token != advTokens.get()) return@synchronized // a newer start/stop superseded this one
                        startPending = false
                        consecutiveFailures = 0
                        lastAdvertiseError = 0
                        lastStartSuccessAt = clock()
                        advertisedMode = mode
                        _advertising.value = true
                        PumpLog.i("BLE advertising ON as $localName ($mode, $reason)")
                    }
                }

                override fun onStartFailure(errorCode: Int) = safe {
                    synchronized(lock) {
                        if (token != advTokens.get()) return@synchronized
                        startPending = false
                        lastAdvertiseError = errorCode
                        if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                            // Our previous advertisement is still running (stop and start raced): it is on.
                            advertisedMode = mode
                            _advertising.value = true
                            PumpLog.i("BLE advertising already running ($mode)")
                        } else {
                            consecutiveFailures++
                            _advertising.value = false
                            PumpLog.w("BLE advertising FAILED: ${advertiseError(errorCode)}, attempt $consecutiveFailures, retry in ${AdvertisingPolicy.backoffMs(consecutiveFailures)} ms")
                        }
                    }
                }
            }
            advertiseCallback = cb
            startPending = true
            PumpLog.d("BLE starting advertising ($mode): $reason")
            adv.startAdvertising(settings, data, scan, cb)
        } catch (t: Throwable) {
            PumpLog.e("startAdvertising failed", t)
            startPending = false
            consecutiveFailures++
            _advertising.value = false
        }
    }

    private fun stopAdvertisingLocked() {
        advTokens.incrementAndGet() // late callbacks of the advertisement being stopped are ignored
        try {
            advertiseCallback?.let { advertiser?.stopAdvertising(it) }
        } catch (t: Throwable) {
            PumpLog.w("stopAdvertising", t)
        }
        advertiseCallback = null
        startPending = false
        advertisedMode = null
        if (_advertising.value) PumpLog.d("BLE advertising stopped")
        _advertising.value = false
    }

    private fun advertiseError(code: Int) = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
        else -> "code $code"
    }

    // ------------------------------------------------------------------ GATT callbacks

    private val callback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) = safe {
            synchronized(lock) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    serviceAdded = true
                    PumpLog.i("BLE GATT service added")
                    startAdvertisingLocked(AdvertisingPolicy.desiredMode(sessionActive), "service added")
                } else {
                    PumpLog.w("BLE service add failed: $status")
                    consecutiveFailures++
                    lastAttemptAt = clock()
                    stopLocked("service add failed ($status)")
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = safe {
            val id = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val gen = generations.incrementAndGet()
                val old = synchronized(lock) {
                    val o = centrals.put(id, Central(device, gen))
                    forceRefreshAt = clock() + POST_CONNECT_REFRESH_MS
                    o?.generation
                }
                PumpLog.i("BLE ${shortId(id)}#$gen GATT connected (status ${GattStatus.describe(status)})")
                // A stale entry for the same address (missed disconnect): retire it before announcing the new one.
                if (old != null) listener.onDisconnected(id, old, "replaced by a new connection from the same address")
                listener.onConnected(id, gen)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val gen = synchronized(lock) { centrals.remove(id)?.generation }
                PumpLog.i("BLE ${shortId(id)}#${gen ?: "?"} GATT disconnected (status ${GattStatus.describe(status)})")
                if (gen != null) listener.onDisconnected(id, gen, "GATT disconnect: ${GattStatus.describe(status)}")
                // Connectable advertising may have been stopped by the stack for the connection: bring it back now.
                synchronized(lock) { if (wanted && server != null && serviceAdded) startAdvertisingLocked(AdvertisingPolicy.desiredMode(sessionActive), "after disconnect") }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = safe {
            synchronized(lock) { centrals[device.address]?.mtu = mtu.coerceAtLeast(DEFAULT_MTU) }
            PumpLog.d("BLE ${shortId(device.address)} MTU $mtu")
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
            if (ok) {
                val gen = synchronized(lock) { centrals[device.address]?.generation }
                if (gen != null) listener.onFragment(device.address, gen, value!!.copyOf())
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) = safe {
            var status = BluetoothGatt.GATT_SUCCESS
            if (descriptor.uuid == GattIds.CCCD && value != null) {
                val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                synchronized(lock) { centrals[device.address]?.subscribed = enable }
                PumpLog.d("BLE ${shortId(device.address)} notifications ${if (enable) "enabled" else "disabled"}")
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
                if (!c.sending) return@synchronized // late callback for something we already gave up on
                c.sending = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    PumpLog.w("BLE ${shortId(device.address)}#${c.generation} notification failed: ${GattStatus.describe(status)}")
                    disconnectLocked(device, "notification failed (${GattStatus.describe(status)})")
                } else {
                    c.retries = 0
                    pumpLocked(c)
                }
            }
        }
    }

    // ------------------------------------------------------------------ LinkTransmitter

    override fun send(centralId: String, generation: Long, fragments: List<ByteArray>): Boolean = synchronized(lock) {
        val c = centrals[centralId]?.takeIf { it.generation == generation } ?: return false
        if (!c.subscribed) return false
        if (c.queue.size + fragments.size > MAX_QUEUE_PER_CENTRAL) return false
        val now = clock()
        if (c.sending && now - c.sendingSince > NOTIFY_STUCK_MS) {
            // The completion callback never came: this connection can not deliver anything any more.
            PumpLog.w("BLE ${shortId(centralId)}#$generation notification stalled for ${(now - c.sendingSince) / 1000} s, disconnecting")
            disconnectLocked(c.device, "notification stalled")
            return false
        }
        fragments.forEach { c.queue.addLast(it) }
        pumpLocked(c)
        true
    }

    override fun maxFragmentSize(centralId: String): Int = synchronized(lock) {
        ((centrals[centralId]?.mtu ?: DEFAULT_MTU) - ATT_OVERHEAD).coerceAtLeast(20)
    }

    override fun disconnect(centralId: String, generation: Long) {
        // Only the connection the engine means: a newer connection of the same device must survive.
        synchronized(lock) { centrals[centralId]?.takeIf { it.generation == generation }?.let { disconnectLocked(it.device, "closed by bridge") } }
    }

    private fun disconnectLocked(device: BluetoothDevice, reason: String) {
        try {
            server?.cancelConnection(device)
        } catch (t: Throwable) {
            PumpLog.w("cancelConnection", t)
        }
        val removed = centrals.remove(device.address)
        // Not inline: the engine may be calling us under its own lock (lock order engine -> gatt).
        if (removed != null) notifier.execute { safe { listener.onDisconnected(device.address, removed.generation, reason) } }
    }

    @SuppressLint("WrongConstant") // pre-33 notify path; lint confuses the deprecated setValue overload
    private fun pumpLocked(c: Central) {
        if (c.sending) return
        val next = c.queue.removeFirstOrNull() ?: return
        val s = server ?: return
        val ch = txChar ?: return
        c.sending = true
        c.sendingSince = clock()
        val status: Int = try {
            if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(c.device, ch, false, next)
            } else {
                @Suppress("DEPRECATION")
                ch.value = next
                @Suppress("DEPRECATION")
                if (s.notifyCharacteristicChanged(c.device, ch, false)) BluetoothGatt.GATT_SUCCESS else -1
            }
        } catch (t: Throwable) {
            PumpLog.w("notify threw", t)
            -2
        }
        if (status == BluetoothGatt.GATT_SUCCESS) return
        // Not queued by the stack. "Busy" (previous notification still in flight) is transient: put the fragment
        // back and retry shortly. Anything that keeps failing ends the connection instead of wedging the queue.
        c.sending = false
        c.retries++
        if (c.retries > NOTIFY_MAX_RETRIES) {
            PumpLog.w("BLE ${shortId(c.device.address)}#${c.generation} notify failed $status after ${c.retries} retries, disconnecting")
            disconnectLocked(c.device, "notify failed ($status)")
            return
        }
        c.queue.addFirst(next)
        val busy = Build.VERSION.SDK_INT >= 33 && status == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
        if (!busy && c.retries == 1) PumpLog.d("BLE notify not accepted (status $status), retrying")
        notifier.schedule({
            safe { synchronized(lock) { if (centrals[c.device.address] === c) pumpLocked(c) } }
        }, NOTIFY_RETRY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun shortId(address: String) = address.takeLast(5)

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            PumpLog.e("GATT callback failure", t)
        }
    }
}
