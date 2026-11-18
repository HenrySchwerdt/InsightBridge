/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.main

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.trio.insightbridge.ui.wizard.VendorSettings

/** Small wrappers that never throw: a missing settings screen falls back to the app details screen. */
internal object SystemIntents {

    private fun tryStart(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }

    fun bluetoothSettings(ctx: Context) {
        if (!tryStart(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) VendorSettings.openAppDetails(ctx)
    }

    fun notificationSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        if (!tryStart(ctx, i)) VendorSettings.openAppDetails(ctx)
    }
}
