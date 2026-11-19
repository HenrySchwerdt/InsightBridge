/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.BuildConfig
import app.trio.insightbridge.service.HealthSnapshot

/** Shares the in-memory log (never contains secrets) as plain text, so users can send it for support. */
object LogShare {

    /** One-line summary of an exception and its causes, e.g. "ConnectionFailedException <- IOException: read failed". */
    fun describe(t: Throwable?): String? {
        if (t == null) return null
        val parts = mutableListOf<String>()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 4) {
            parts += cur.javaClass.simpleName + (cur.message?.takeIf { it.isNotBlank() }?.let { ": " + it.take(160) } ?: "")
            cur = cur.cause.takeIf { it !== cur }
            depth++
        }
        return parts.joinToString(" <- ")
    }

    /** One-line connection summary placed above the log so a report shows the link situation at a glance. */
    fun healthSummary(h: HealthSnapshot): String = buildString {
        append("Service ").append(if (h.serviceRunning) "running" else "NOT running")
        append(", Bluetooth ").append(if (h.bluetoothOn) "on" else "OFF")
        append(", GATT server ").append(if (h.serverOpen) "open" else "CLOSED")
        append(", advertising ").append(if (h.advertising) "on" else "OFF")
        if (h.advertiseError != 0) append(" (last error ").append(h.advertiseError).append(')')
        append("\niPhone: ").append(if (h.phonePaired) "paired" else "not paired")
        append(", link ").append(h.phoneLink.name.lowercase())
        append(", ").append(h.gattConnections).append(" BLE connection(s), sessions in last 24 h: ").append(h.sessions24h)
        append("\nLast disconnect: ").append(h.lastDisconnectReason ?: "none")
        if (h.lastDisconnectAt > 0) append(" (").append(Format.ago(h.now, h.lastDisconnectAt)).append(')')
        append("\nPump: ").append(if (h.pumpPaired) "paired" else "not paired")
        append(", link ").append(h.pumpLink.wire)
        append(", last contact ").append(Format.ago(h.now, h.lastPumpContact))
        append(if (h.batteryExempt) ", battery optimisation off" else ", BATTERY OPTIMISATION ON")
    }

    fun buildText(lines: List<LogLine>, summary: String? = null): String = buildString {
        appendLine("InsightBridge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        if (summary != null) appendLine(summary)
        appendLine()
        lines.forEach { appendLine(LogBuffer.format(it)) }
    }

    /** Opens the share sheet. False if nothing could handle it (the caller then points at "Copy log"). */
    fun share(context: Context, lines: List<LogLine>, summary: String? = null): Boolean = try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "InsightBridge log")
            putExtra(Intent.EXTRA_TEXT, buildText(lines, summary))
        }
        context.startActivity(Intent.createChooser(send, "InsightBridge log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) {
        PumpLog.w("Share log failed", t)
        false
    }

    fun copy(context: Context, lines: List<LogLine>, summary: String? = null): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("InsightBridge log", buildText(lines, summary)))
        true
    } catch (t: Throwable) {
        PumpLog.w("Copy log failed", t)
        false
    }
}
