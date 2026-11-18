/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Permission and platform state checks used by the service, watchdog and UI. */
object Perms {

    fun bluetoothRuntimePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun allRuntimePermissions(): List<String> =
        bluetoothRuntimePermissions() + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun bluetoothGranted(c: Context): Boolean = bluetoothRuntimePermissions().all { granted(c, it) }

    fun notificationsPermitted(c: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS)) &&
            (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()

    fun batteryExempt(c: Context): Boolean =
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

    fun bluetoothOn(c: Context): Boolean = try {
        (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }
}
