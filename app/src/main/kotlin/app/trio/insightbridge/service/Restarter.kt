/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.aaps.pump.insight.utils.PumpLog

/** Schedules a service restart via AlarmManager (used after crashes and by the watchdog). */
object Restarter {

    const val ACTION_RESTART = "app.trio.insightbridge.action.RESTART"

    fun schedule(context: Context, delayMs: Long) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, RestartReceiver::class.java).setAction(ACTION_RESTART)
            val pi = PendingIntent.getBroadcast(context, 42, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val at = System.currentTimeMillis() + delayMs
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (t: Throwable) {
            PumpLog.e("Could not schedule restart", t)
        }
    }
}
