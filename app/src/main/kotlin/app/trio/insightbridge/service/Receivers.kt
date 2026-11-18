/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.BridgeApp
import app.trio.insightbridge.util.launchSafely

/** Boot, locked boot, app update: bring the bridge up again. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val runtime = BridgeApp.runtime(context)
            PumpLog.i("Start trigger: ${intent.action?.substringAfterLast('.')}")
            if (runtime.settings.setupDone) {
                BridgeService.start(context)
                val unlocked = context.getSystemService(Context.USER_SERVICE).let { (it as UserManager).isUserUnlocked }
                if (unlocked) Watchdog.schedule(context)
            }
        } catch (t: Throwable) {
            PumpLog.e("BootReceiver failed", t)
        }
    }
}

/** Fired by the crash handler / watchdog alarm. */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val runtime = BridgeApp.runtime(context)
            if (runtime.settings.setupDone) BridgeService.start(context)
        } catch (t: Throwable) {
            PumpLog.e("RestartReceiver failed", t)
        }
    }
}

/** Notification action buttons for pump alerts: explicit user taps only. */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val runtime = BridgeApp.runtime(context)
        val id = intent.getIntExtra("alertId", -1)
        val type = intent.getStringExtra("alertType")
        val action = intent.getStringExtra("action")
        runtime.scope.launchSafely {
            try {
                if (id >= 0 && type != null) {
                    if (action == "confirm") runtime.userConfirmAlert(id, type) else if (action == "snooze") runtime.userSnoozeAlert(id, type)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
