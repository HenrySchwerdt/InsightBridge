/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.store

import app.trio.insightbridge.engine.TextFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Non-secret persistent state (Direct Boot safe plain JSON file): link mode, simulator flag,
 * heartbeat, crash markers, setup progress.
 */
class SettingsStore(private val file: TextFile) {

    private val map = HashMap<String, String>()

    private var writable = true

    init {
        try {
            file.read()?.let { text ->
                try {
                    (Json.parseToJsonElement(text) as JsonObject).forEach { (k, v) -> map[k] = (v as JsonPrimitive).content }
                } catch (_: Exception) {
                    // corrupt: keep the file for diagnosis, start with defaults
                    map.clear()
                    try { file.quarantine() } catch (_: Exception) { writable = false }
                }
            }
        } catch (_: Exception) {
            // unreadable (transient): run with defaults but never overwrite the file
            writable = false
        }
    }

    @Synchronized fun get(key: String): String? = map[key]
    @Synchronized fun getLong(key: String, default: Long = 0) = map[key]?.toLongOrNull() ?: default
    @Synchronized fun getBoolean(key: String, default: Boolean = false) = map[key]?.toBooleanStrictOrNull() ?: default

    @Synchronized
    fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
        if (!writable) return
        try {
            file.write(buildJsonObject { map.forEach { (k, v) -> put(k, v) } }.toString())
        } catch (_: Exception) {
        }
    }

    fun putLong(key: String, v: Long) = put(key, v.toString())
    fun putBoolean(key: String, v: Boolean) = put(key, v.toString())

    /**
     * The simulator must never resume by itself after a restart: called once at process start, it switches a
     * stale flag off so the developer has to enable (and confirm) it again. Returns true if it was on.
     */
    fun clearStaleSimulatorFlag(): Boolean {
        val was = simulatorEnabled
        if (was) simulatorEnabled = false
        return was
    }

    var simulatorEnabled: Boolean
        get() = getBoolean(SIMULATOR)
        set(v) = putBoolean(SIMULATOR, v)

    var alwaysOnLink: Boolean
        get() = getBoolean(ALWAYS_ON, false)
        set(v) = putBoolean(ALWAYS_ON, v)

    var setupDone: Boolean
        get() = getBoolean(SETUP_DONE)
        set(v) = putBoolean(SETUP_DONE, v)

    var crashPending: Boolean
        get() = getBoolean(CRASH_PENDING)
        set(v) = putBoolean(CRASH_PENDING, v)

    companion object {

        const val SIMULATOR = "simulator"
        const val ALWAYS_ON = "alwaysOnLink"
        const val SETUP_DONE = "setupDone"
        const val CRASH_PENDING = "crashPending"
        const val HEARTBEAT = "heartbeat"
        const val SERVICE_STARTED = "serviceStarted"
    }
}
