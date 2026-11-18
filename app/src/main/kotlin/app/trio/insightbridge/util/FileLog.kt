/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.util

import java.io.File

/**
 * Append-only crash/warning log with a size cap (rotates to .1). Used by the uncaught exception
 * handler: no coroutines, no allocation-heavy code, never throws.
 */
class FileLog(private val file: File, private val maxBytes: Long = 256 * 1024) {

    @Synchronized
    fun append(line: String) {
        try {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > maxBytes) {
                val old = File(file.path + ".1")
                old.delete()
                file.renameTo(old)
            }
            file.appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun readTail(maxChars: Int = 8000): String = try {
        if (file.exists()) file.readText().takeLast(maxChars) else ""
    } catch (_: Throwable) {
        ""
    }
}
