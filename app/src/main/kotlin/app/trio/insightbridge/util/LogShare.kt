/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.util

import android.content.Context
import android.content.Intent
import android.os.Build
import app.trio.insightbridge.BuildConfig

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

    fun buildText(lines: List<LogLine>): String = buildString {
        appendLine("InsightBridge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine()
        lines.forEach { appendLine(LogBuffer.format(it)) }
    }

    fun share(context: Context, lines: List<LogLine>) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "InsightBridge log")
            putExtra(Intent.EXTRA_TEXT, buildText(lines))
        }
        context.startActivity(Intent.createChooser(send, "InsightBridge log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
