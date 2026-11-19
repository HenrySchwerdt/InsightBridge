/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.engine

/**
 * "Connection health" numbers for the main screen and the shared log: how many iPhone sessions were established in
 * the last 24 h and why the last link ended. Optionally persisted through [read]/[write] (the settings store) so the
 * picture survives a service restart. Contains no identifiers, only times and reason texts.
 */
class LinkStats(
    private val clock: () -> Long,
    private val read: (String) -> String? = { null },
    private val write: (String, String?) -> Unit = { _, _ -> }
) {

    data class Disconnect(val at: Long, val reason: String, val wasSession: Boolean)

    companion object {

        const val WINDOW_MS = 24 * 3_600_000L
        const val MAX_SESSIONS = 300
        const val KEY_SESSIONS = "linkSessions"
        const val KEY_LAST = "linkLastDisconnect"
    }

    private val sessions = ArrayDeque<Long>()
    private var last: Disconnect? = null

    init {
        read(KEY_SESSIONS)?.split(',')?.mapNotNull { it.toLongOrNull() }?.forEach { sessions.addLast(it) }
        read(KEY_LAST)?.let { raw ->
            val parts = raw.split('|', limit = 3)
            val at = parts.getOrNull(0)?.toLongOrNull()
            if (at != null && parts.size == 3) last = Disconnect(at, parts[2], parts[1] == "1")
        }
        prune(clock())
    }

    @Synchronized
    fun sessionEstablished() {
        val now = clock()
        sessions.addLast(now)
        prune(now)
        write(KEY_SESSIONS, sessions.joinToString(","))
    }

    @Synchronized
    fun linkClosed(reason: String, wasSession: Boolean) {
        val d = Disconnect(clock(), reason.take(120), wasSession)
        last = d
        write(KEY_LAST, "${d.at}|${if (wasSession) 1 else 0}|${d.reason.replace('|', '/')}")
    }

    @Synchronized
    fun sessionsLast24h(): Int {
        prune(clock())
        return sessions.size
    }

    @Synchronized fun lastDisconnect(): Disconnect? = last

    private fun prune(now: Long) {
        while (sessions.isNotEmpty() && now - sessions.first() > WINDOW_MS) sessions.removeFirst()
        while (sessions.size > MAX_SESSIONS) sessions.removeFirst()
    }
}
