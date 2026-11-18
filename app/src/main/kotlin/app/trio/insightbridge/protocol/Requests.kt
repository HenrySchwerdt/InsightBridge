/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One basal profile segment as requested by the phone. */
data class BasalSegment(val durationMinutes: Int, val rate: BigDecimal)

/** Parsed (structure and types checked, ranges NOT yet) application requests. */
sealed interface Request {

    val op: String

    data class Hello(val protocol: Int, val app: String?) : Request { override val op = "hello" }
    data object GetStatus : Request { override val op = "getStatus" }
    data class DeliverBolus(val amount: BigDecimal, val requestId: String) : Request { override val op = "deliverBolus" }
    data class CancelBolus(val bolusId: Int) : Request { override val op = "cancelBolus" }
    data class SetTbr(val percent: Int, val duration: Int, val requestId: String) : Request { override val op = "setTBR" }
    data class CancelTbr(val requestId: String) : Request { override val op = "cancelTBR" }
    data class SetOperatingMode(val started: Boolean, val requestId: String) : Request { override val op = "setOperatingMode" }
    data class SetBasalProfile(val segments: List<BasalSegment>, val requestId: String) : Request { override val op = "setBasalProfile" }
    data class SetDateTime(val utc: Instant, val tzOffsetMinutes: Int) : Request { override val op = "setDateTime" }
    data class ReadHistory(val sinceSequence: Long?, val max: Int) : Request { override val op = "readHistory" }
    data class ConfirmAlert(val alertId: Int, val alertType: String) : Request { override val op = "confirmAlert" }
    data class SnoozeAlert(val alertId: Int, val alertType: String) : Request { override val op = "snoozeAlert" }
    data object PumpPairingStatus : Request { override val op = "pumpPairingStatus" }
    data class GetRequestOutcome(val requestId: String) : Request { override val op = "getRequestOutcome" }

    /** Ops that carry a requestId and are executed at most once. */
    val requestIdOrNull: String?
        get() = when (this) {
            is DeliverBolus -> requestId
            is SetTbr -> requestId
            is CancelTbr -> requestId
            is SetOperatingMode -> requestId
            is SetBasalProfile -> requestId
            else -> null
        }
}

/** A request frame: `{"id":..,"op":..,"args":{..}}`. */
data class RequestFrame(val id: Long, val request: Request)

/** Thrown when a frame is so malformed that not even an id can be recovered. */
class UnparseableFrame(message: String) : Exception(message)

object RequestParser {

    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private fun bad(msg: String): Nothing = throw PumpOpException(ErrorCode.INVALID_ARGS, msg)

    /** Returns the request id from a raw frame if it is a valid uint32, else null. */
    fun idOf(obj: JsonObject): Long? {
        val el = obj["id"] as? JsonPrimitive ?: return null
        if (el.isString) return null
        val bd = el.content.toBigDecimalOrNull() ?: return null
        val l = runCatching { bd.toBigIntegerExact().takeIf { it.bitLength() <= 63 }?.toLong() }.getOrNull() ?: return null
        return if (l in 0..0xFFFF_FFFFL) l else null
    }

    fun parseObject(bytes: ByteArray): JsonObject {
        val el = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw UnparseableFrame("not JSON")
        }
        return el as? JsonObject ?: throw UnparseableFrame("not an object")
    }

    /** Parses structure and types; throws [PumpOpException] (INVALID_ARGS) with the frame id known. */
    fun parseRequest(frame: JsonObject): Request {
        val op = (frame["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("missing op")
        val args = when (val a = frame["args"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> a
            else -> bad("args must be an object")
        }
        return when (op) {
            "hello" -> Request.Hello(int(args, "protocol"), optString(args, "app"))
            "getStatus" -> Request.GetStatus
            "deliverBolus" -> Request.DeliverBolus(decimal(args, "amount"), uuid(args))
            "cancelBolus" -> Request.CancelBolus(int(args, "bolusId"))
            "setTBR" -> Request.SetTbr(int(args, "percent"), int(args, "duration"), uuid(args))
            "cancelTBR" -> Request.CancelTbr(uuid(args))
            "setOperatingMode" -> Request.SetOperatingMode(
                when (string(args, "mode")) {
                    "started" -> true
                    "stopped" -> false
                    else -> bad("mode must be started or stopped")
                },
                uuid(args)
            )
            "setBasalProfile" -> {
                val arr = args["segments"] as? JsonArray ?: bad("segments must be an array")
                if (arr.size > 64) bad("too many segments")
                Request.SetBasalProfile(
                    arr.map { seg ->
                        val o = seg as? JsonObject ?: bad("segment must be an object")
                        BasalSegment(int(o, "duration"), decimal(o, "rate"))
                    },
                    uuid(args)
                )
            }
            "setDateTime" -> {
                val utc = try {
                    Instant.parse(string(args, "utc"))
                } catch (e: Exception) {
                    bad("utc must be ISO-8601 UTC")
                }
                Request.SetDateTime(utc, int(args, "tzOffsetMinutes"))
            }
            "readHistory" -> Request.ReadHistory(
                when (val s = args["sinceSequence"]) {
                    null, JsonNull -> null
                    else -> longOf(s, "sinceSequence")
                },
                int(args, "max")
            )
            "confirmAlert" -> Request.ConfirmAlert(int(args, "alertId"), string(args, "alertType"))
            "snoozeAlert" -> Request.SnoozeAlert(int(args, "alertId"), string(args, "alertType"))
            "pumpPairingStatus" -> Request.PumpPairingStatus
            "getRequestOutcome" -> Request.GetRequestOutcome(uuid(args))
            else -> bad("unknown op")
        }
    }

    private fun prim(o: JsonObject, name: String): JsonPrimitive {
        val p = o[name] as? JsonPrimitive ?: bad("missing or invalid $name")
        if (p is JsonNull) bad("$name must not be null")
        return p
    }

    private fun string(o: JsonObject, name: String): String {
        val p = prim(o, name)
        if (!p.isString) bad("$name must be a string")
        return p.content
    }

    private fun optString(o: JsonObject, name: String): String? {
        val p = o[name] as? JsonPrimitive ?: return null
        return if (p is JsonNull) null else if (p.isString) p.content.take(64) else bad("$name must be a string")
    }

    private fun decimal(o: JsonObject, name: String): BigDecimal {
        val p = prim(o, name)
        if (p.isString) bad("$name must be a number")
        // BigDecimal rejects NaN / Infinity literals; exponent forms are fine.
        return p.content.toBigDecimalOrNull()?.takeIf { it.scale() < 18 && it.precision() < 40 } ?: bad("$name is not a valid number")
    }

    private fun int(o: JsonObject, name: String): Int {
        val v = longOf(prim(o, name), name)
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) bad("$name out of range")
        return v.toInt()
    }

    private fun longOf(el: JsonElement, name: String): Long {
        val p = el as? JsonPrimitive ?: bad("$name must be an integer")
        if (p.isString) bad("$name must be an integer")
        val bd = p.content.toBigDecimalOrNull() ?: bad("$name must be an integer")
        // BigInteger.longValueExact needs API 31; check the range by hand (minSdk 26).
        val exact = try {
            bd.toBigIntegerExact()
        } catch (e: ArithmeticException) {
            bad("$name must be an integer")
        }
        if (exact.bitLength() > 63) bad("$name out of range")
        return exact.toLong()
    }

    private fun uuid(o: JsonObject): String {
        val s = string(o, "requestId")
        if (!UUID_RE.matches(s)) bad("requestId must be a UUID")
        return s.lowercase()
    }
}
