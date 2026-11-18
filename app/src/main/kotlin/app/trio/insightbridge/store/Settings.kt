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

    init {
        try {
            file.read()?.let { text ->
                (Json.parseToJsonElement(text) as JsonObject).forEach { (k, v) -> map[k] = (v as JsonPrimitive).content }
            }
        } catch (_: Exception) {
            map.clear()
        }
    }

    @Synchronized fun get(key: String): String? = map[key]
    @Synchronized fun getLong(key: String, default: Long = 0) = map[key]?.toLongOrNull() ?: default
    @Synchronized fun getBoolean(key: String, default: Boolean = false) = map[key]?.toBooleanStrictOrNull() ?: default

    @Synchronized
    fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
        try {
            file.write(buildJsonObject { map.forEach { (k, v) -> put(k, v) } }.toString())
        } catch (_: Exception) {
        }
    }

    fun putLong(key: String, v: Long) = put(key, v.toString())
    fun putBoolean(key: String, v: Boolean) = put(key, v.toString())

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
