/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.BridgeApp
import app.trio.insightbridge.BridgeRuntime

/**
 * The foreground service (type connectedDevice) that keeps the pump link and the BLE GATT server
 * alive. START_STICKY, restarted on boot / app update / crash / by the watchdog.
 */
class BridgeService : Service() {

    companion object {

        @Volatile var running = false
            private set
        @Volatile var lastHeartbeat = 0L
            private set

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
            } catch (t: Throwable) {
                // Background start refused (or permission missing): tell the user instead of failing silently.
                PumpLog.e("Could not start the foreground service", t)
                try { BridgeApp.runtime(context).notifications.notRunning() } catch (_: Throwable) {}
            }
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, BridgeService::class.java)) } catch (_: Throwable) {}
        }
    }

    private lateinit var runtime: BridgeRuntime
    private var core: BridgeCore? = null
    private var btReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runtime = BridgeApp.runtime(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = runtime.notifications.buildStatus(runtime.health.value.copy(now = System.currentTimeMillis()))
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, Notifications.ID_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(Notifications.ID_STATUS, notification)
            }
        } catch (t: Throwable) {
            // e.g. SecurityException when Bluetooth permissions are missing on Android 14+
            PumpLog.e("startForeground failed", t)
            runtime.notifications.permissionsMissing()
            stopSelf()
            return START_NOT_STICKY
        }
        if (core == null) {
            val c = BridgeCore(runtime)
            core = c
            c.start { lastHeartbeat = it }
            registerBluetoothReceiver(c)
            running = true
            lastHeartbeat = System.currentTimeMillis()
            runtime.settings.putLong(app.trio.insightbridge.store.SettingsStore.SERVICE_STARTED, lastHeartbeat)
            if (isUnlocked()) Watchdog.schedule(this)
        }
        return START_STICKY
    }

    private fun isUnlocked() =
        (getSystemService(Context.USER_SERVICE) as android.os.UserManager).isUserUnlocked

    private fun registerBluetoothReceiver(c: BridgeCore) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                            BluetoothAdapter.STATE_OFF -> c.onBluetoothState(false)
                            BluetoothAdapter.STATE_ON -> c.onBluetoothState(true)
                        }
                    }
                } catch (t: Throwable) {
                    PumpLog.e("Bluetooth state receiver failed", t)
                }
            }
        }
        btReceiver = r
        ContextCompat.registerReceiver(this, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not stop the bridge.
        super.onTaskRemoved(rootIntent)
        if (runtime.settings.setupDone) Restarter.schedule(this, 1_500)
    }

    override fun onDestroy() {
        running = false
        try { btReceiver?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        btReceiver = null
        try { core?.stop() } catch (t: Throwable) { PumpLog.e("core stop failed", t) }
        core = null
        super.onDestroy()
        // If the system (not the user) destroys us, START_STICKY restarts us; the watchdog is the backstop.
    }
}
