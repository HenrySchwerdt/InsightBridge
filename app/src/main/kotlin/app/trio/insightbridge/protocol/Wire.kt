/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JSON encoding of responses, results and events (PROTOCOL.md section 5). */
object Wire {

    val json = Json {
        explicitNulls = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(obj: JsonObject): ByteArray = json.encodeToString(JsonObject.serializer(), obj).toByteArray(Charsets.UTF_8)

    fun ok(id: Long, result: JsonObject): JsonObject = buildJsonObject {
        put("id", id)
        put("ok", true)
        put("result", result)
    }

    fun error(id: Long, code: ErrorCode, message: String, uncertain: Boolean = false): JsonObject = buildJsonObject {
        put("id", id)
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code.wire)
            put("message", message)
            put("uncertain", uncertain)
        })
    }

    fun event(name: String, data: JsonElement): JsonObject = buildJsonObject {
        put("evt", name)
        put("data", data)
    }

    fun num(v: BigDecimal): JsonPrimitive = JsonPrimitive(v.toDouble())
    fun num(v: Double?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    inline fun <reified T> toJson(v: T): JsonElement = json.encodeToJsonElement(kotlinx.serialization.serializer<T>(), v)

    // ---- results (section 5.1) ----

    fun helloResult(bridgeVersion: String, pumpPaired: Boolean) = buildJsonObject {
        put("protocol", 2)
        put("bridgeVersion", bridgeVersion)
        put("pumpPaired", pumpPaired)
    }

    fun bolusResult(bolusId: Int, amount: BigDecimal) = buildJsonObject {
        put("bolusId", bolusId)
        put("amount", num(amount))
    }

    fun cancelBolusResult(delivered: Double?) = buildJsonObject { put("delivered", num(delivered)) }

    fun tbrResult(percent: Int, duration: Int) = buildJsonObject {
        put("percent", percent)
        put("duration", duration)
    }

    fun emptyResult() = JsonObject(emptyMap())

    fun modeResult(started: Boolean) = buildJsonObject { put("mode", if (started) WireMode.STARTED else WireMode.STOPPED) }

    fun profileResult(activeProfile: Int) = buildJsonObject { put("activeProfile", activeProfile) }

    fun historyResult(page: HistoryPage) = buildJsonObject {
        put("events", JsonArray(page.events.map { toJson(it) }))
        put("lastSequence", page.lastSequence)
        put("more", page.more)
    }

    /** getRequestOutcome result (PROTOCOL.md v2). */
    fun outcomeResult(state: String, result: JsonObject?, errorCode: ErrorCode?, message: String?, uncertain: Boolean, completedAt: String?) = buildJsonObject {
        put("state", state)
        put("result", result ?: JsonNull)
        put("error", if (errorCode == null) JsonNull else buildJsonObject {
            put("code", errorCode.wire); put("message", message ?: ""); put("uncertain", uncertain)
        })
        put("completedAt", if (completedAt == null) JsonNull else JsonPrimitive(completedAt))
    }

    /** ping result (PROTOCOL.md 5.1): no pump traffic. */
    fun pingResult(uptimeSeconds: Long, pumpLink: String, statusTimestamp: String?) = buildJsonObject {
        put("uptimeSeconds", uptimeSeconds)
        put("pumpLink", pumpLink)
        put("statusTimestamp", if (statusTimestamp == null) JsonNull else JsonPrimitive(statusTimestamp))
    }

    fun pairingStatusResult(paired: Boolean, serial: String?, bluetoothOn: Boolean) = buildJsonObject {
        put("paired", paired)
        put("serial", if (serial == null) JsonNull else JsonPrimitive(serial))
        put("bridgeBluetoothOn", bluetoothOn)
    }

    // ---- events (section 5.5) ----

    fun statusEvent(s: StatusDto) = event("status", toJson(s))
    fun alertEvent(a: AlertDto) = event("alert", toJson(a))
    fun bolusProgressEvent(p: BolusProgressDto) = event("bolusProgress", toJson(p))
    fun pumpLinkEvent(state: PumpLinkState) = event("pumpLink", toJson(PumpLinkDto(state.wire)))
    fun encodeStatus(s: StatusDto): String = json.encodeToString(s)
}
