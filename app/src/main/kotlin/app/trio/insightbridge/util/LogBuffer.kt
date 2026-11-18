/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.util

import app.aaps.pump.insight.utils.PumpLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogLine(val time: Long, val level: PumpLog.Level, val text: String)

/**
 * Bounded in-memory log (ring buffer). Never contains secrets: callers log events and codes, never
 * keys, nonces, verification strings or payloads.
 */
class LogBuffer(private val capacity: Int = 500, private val clock: () -> Long = System::currentTimeMillis) : PumpLog.Sink {

    private val lines = ArrayDeque<LogLine>()
    private val _flow = MutableStateFlow<List<LogLine>>(emptyList())
    val flow: StateFlow<List<LogLine>> = _flow.asStateFlow()

    @Volatile var persistent: ((String) -> Unit)? = null

    val size: Int @Synchronized get() = lines.size

    @Synchronized
    fun add(level: PumpLog.Level, text: String) {
        if (lines.size >= capacity) lines.removeFirst()
        val line = LogLine(clock(), level, text.take(300))
        lines.addLast(line)
        _flow.value = lines.toList()
        if (level == PumpLog.Level.ERROR || level == PumpLog.Level.WARN) persistent?.invoke(format(line))
    }

    override fun log(level: PumpLog.Level, message: String, throwable: Throwable?) {
        val t = throwable?.let { " [${it.javaClass.simpleName}${it.message?.let { m -> ": " + m.take(80) } ?: ""}]" } ?: ""
        add(level, message + t)
    }

    companion object {

        private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        fun format(l: LogLine) = "${fmt.format(Instant.ofEpochMilli(l.time))} ${l.level.name.first()} ${l.text}"
    }
}
