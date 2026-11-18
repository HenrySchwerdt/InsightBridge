/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.util

/** Small formatting helpers shared by UI and notifications (pure, unit tested). */
object Format {

    /** "3d 4h", "2h 05m", "4m 10s", "12s". */
    fun duration(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
            m > 0 -> "${m}m ${sec.toString().padStart(2, '0')}s"
            else -> "${sec}s"
        }
    }

    fun ago(now: Long, then: Long): String = if (then <= 0) "never" else duration(now - then) + " ago"
}
