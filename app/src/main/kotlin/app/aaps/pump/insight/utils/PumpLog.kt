/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.aaps.pump.insight.utils

/**
 * Minimal logging sink replacing AAPSLogger. The bridge installs a bounded ring buffer here.
 * Messages must never contain keys, nonces, verification material or other secrets.
 */
object PumpLog {

    interface Sink {

        fun log(level: Level, message: String, throwable: Throwable?)
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile var sink: Sink? = null

    fun d(message: String) = emit(Level.DEBUG, message, null)
    fun i(message: String) = emit(Level.INFO, message, null)
    fun w(message: String, t: Throwable? = null) = emit(Level.WARN, message, t)
    fun e(message: String, t: Throwable? = null) = emit(Level.ERROR, message, t)

    private fun emit(level: Level, message: String, t: Throwable?) {
        try {
            sink?.log(level, message, t)
        } catch (_: Throwable) {
            // logging must never break the caller
        }
    }
}
