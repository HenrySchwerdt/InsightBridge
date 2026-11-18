/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.trio.insightbridge.R
import app.trio.insightbridge.engine.PhoneLinkState
import app.trio.insightbridge.protocol.AlertDto
import app.trio.insightbridge.protocol.PumpLinkState
import app.trio.insightbridge.ui.MainActivity
import app.trio.insightbridge.ui.alerts.AlertInfo
import app.trio.insightbridge.util.Format

/** All notifications of the bridge. Pump alerts are shown but never confirmed/snoozed automatically. */
@SuppressLint("MissingPermission") // POST_NOTIFICATIONS is requested during onboarding; failures are swallowed
class Notifications(private val context: Context) {

    companion object {

        const val CH_STATUS = "status"
        const val CH_ALERT = "pump_alert"
        const val CH_HEALTH = "health"
        const val ID_STATUS = 1
        const val ID_ALERT = 2
        const val ID_PUMP_UNREACHABLE = 3
        const val ID_RESTARTED = 4
        const val ID_PERMISSIONS = 5
        const val ID_NOT_RUNNING = 6
        const val ID_NAME_HINT = 7
    }

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, context.getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_HEALTH, context.getString(R.string.channel_health), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun linkText(s: PumpLinkState) = context.getString(
        when (s) {
            PumpLinkState.CONNECTED -> R.string.state_connected
            PumpLinkState.CONNECTING -> R.string.state_connecting
            PumpLinkState.DISCONNECTED -> R.string.state_disconnected
            PumpLinkState.NOT_PAIRED -> R.string.state_not_paired
        }
    )

    private fun phoneText(s: PhoneLinkState) = context.getString(
        when (s) {
            PhoneLinkState.SESSION -> R.string.state_connected
            PhoneLinkState.HANDSHAKE -> R.string.state_connecting
            PhoneLinkState.NONE -> R.string.state_disconnected
        }
    )

    fun buildStatus(h: HealthSnapshot): Notification {
        val title = if (h.simulated) context.getString(R.string.sim_banner) else context.getString(R.string.notif_status_title)
        val line1 = context.getString(R.string.notif_status_links, linkText(h.pumpLink), phoneText(h.phoneLink))
        val line2 = context.getString(
            R.string.notif_status_health, Format.duration(h.uptimeMs), Format.ago(h.now, h.lastPumpContact), Format.ago(h.now, h.lastPhoneRequest)
        )
        return NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText(line1)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$line1\n$line2"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(if (h.simulated) 0xFFC62828.toInt() else 0xFF006874.toInt())
            .setContentIntent(openApp())
            .build()
    }

    fun updateStatus(h: HealthSnapshot) {
        try { nm.notify(ID_STATUS, buildStatus(h)) } catch (_: Throwable) {}
    }

    fun alert(a: AlertDto) {
        try {
            val info = AlertInfo.of(a.alertType)
            val title = context.getString(info.titleRes)
            fun action(label: Int, act: String, req: Int) = NotificationCompat.Action.Builder(
                0, context.getString(label),
                PendingIntent.getBroadcast(
                    context, req,
                    Intent(context, AlertActionReceiver::class.java).putExtra("alertId", a.alertId).putExtra("alertType", a.alertType).putExtra("action", act),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            ).build()
            val n = NotificationCompat.Builder(context, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentTitle(context.getString(R.string.notif_alert_title, title))
                .setContentText(context.getString(info.actionRes))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(info.actionRes)))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOnlyAlertOnce(false)
                .setAutoCancel(false)
                .setContentIntent(openApp())
                .addAction(action(R.string.action_confirm_alert, "confirm", 10))
                .addAction(action(R.string.action_snooze_alert, "snooze", 11))
                .build()
            nm.notify(ID_ALERT, n)
        } catch (_: Throwable) {}
    }

    fun clearAlert() = try { nm.cancel(ID_ALERT) } catch (_: Throwable) {}

    private fun health(id: Int, titleRes: Int, textRes: Int) {
        try {
            nm.notify(
                id,
                NotificationCompat.Builder(context, CH_HEALTH)
                    .setSmallIcon(R.drawable.ic_stat_bridge)
                    .setContentTitle(context.getString(titleRes))
                    .setContentText(context.getString(textRes))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(textRes)))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .build()
            )
        } catch (_: Throwable) {}
    }

    fun pumpUnreachable() = health(ID_PUMP_UNREACHABLE, R.string.notif_pump_unreachable_title, R.string.notif_pump_unreachable_text)
    fun clearPumpUnreachable() = try { nm.cancel(ID_PUMP_UNREACHABLE) } catch (_: Throwable) {}
    fun restartedAfterCrash() = health(ID_RESTARTED, R.string.notif_restarted_title, R.string.notif_restarted_text)
    fun permissionsMissing() = health(ID_PERMISSIONS, R.string.notif_permissions_title, R.string.notif_permissions_text)
    fun adapterRenamedHint() = health(ID_NAME_HINT, R.string.notif_name_hint_title, R.string.notif_name_hint_text)
    fun notRunning() = health(ID_NOT_RUNNING, R.string.notif_not_running_title, R.string.notif_not_running_text)
}
