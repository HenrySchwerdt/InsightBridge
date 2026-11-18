/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WireJsonTest {

    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    private fun parse(op: String, args: String = "{}"): Request =
        RequestParser.parseRequest(RequestParser.parseObject("""{"id":5,"op":"$op","args":$args}""".toByteArray()))

    private fun invalid(op: String, args: String) {
        val e = assertThrows<PumpOpException> { parse(op, args) }
        assertThat(e.code).isEqualTo(ErrorCode.INVALID_ARGS)
    }

    // ---------------- requests: every op ----------------

    @Test fun hello() = assertThat(parse("hello", """{"protocol":2,"app":"Trio 1.2"}""")).isEqualTo(Request.Hello(2, "Trio 1.2"))
    @Test fun getRequestOutcome() = assertThat(parse("getRequestOutcome", """{"requestId":"$uuid"}""")).isEqualTo(Request.GetRequestOutcome(uuid))
    @Test fun getStatus() = assertThat(parse("getStatus")).isEqualTo(Request.GetStatus)
    @Test fun pumpPairingStatus() = assertThat(parse("pumpPairingStatus")).isEqualTo(Request.PumpPairingStatus)

    @Test fun deliverBolus() {
        val r = parse("deliverBolus", """{"amount":1.35,"requestId":"$uuid"}""") as Request.DeliverBolus
        assertThat(r.amount).isEqualTo(BigDecimal("1.35"))
        assertThat(r.requestId).isEqualTo(uuid)
        assertThat(r.requestIdOrNull).isEqualTo(uuid)
    }

    @Test fun deliverBolusKeepsDecimalExactness() {
        val r = parse("deliverBolus", """{"amount":0.1,"requestId":"$uuid"}""") as Request.DeliverBolus
        assertThat(r.amount.compareTo(BigDecimal("0.1"))).isEqualTo(0)
        val e = parse("deliverBolus", """{"amount":1E-1,"requestId":"$uuid"}""") as Request.DeliverBolus
        assertThat(e.amount.compareTo(BigDecimal("0.1"))).isEqualTo(0)
    }

    @Test fun cancelBolus() = assertThat(parse("cancelBolus", """{"bolusId":12}""")).isEqualTo(Request.CancelBolus(12))
    @Test fun setTbr() = assertThat(parse("setTBR", """{"percent":120,"duration":60,"requestId":"$uuid"}""")).isEqualTo(Request.SetTbr(120, 60, uuid))
    @Test fun cancelTbr() = assertThat(parse("cancelTBR", """{"requestId":"$uuid"}""")).isEqualTo(Request.CancelTbr(uuid))

    @Test fun setOperatingMode() {
        assertThat(parse("setOperatingMode", """{"mode":"started","requestId":"$uuid"}""")).isEqualTo(Request.SetOperatingMode(true, uuid))
        assertThat(parse("setOperatingMode", """{"mode":"stopped","requestId":"$uuid"}""")).isEqualTo(Request.SetOperatingMode(false, uuid))
        invalid("setOperatingMode", """{"mode":"paused","requestId":"$uuid"}""")
    }

    @Test fun setBasalProfile() {
        val r = parse("setBasalProfile", """{"segments":[{"duration":720,"rate":0.5},{"duration":720,"rate":0.85}],"requestId":"$uuid"}""") as Request.SetBasalProfile
        assertThat(r.segments).hasSize(2)
        assertThat(r.segments[1].rate.compareTo(BigDecimal("0.85"))).isEqualTo(0)
        assertThat(r.segments[0].durationMinutes).isEqualTo(720)
    }

    @Test fun setDateTime() {
        val r = parse("setDateTime", """{"utc":"2026-11-18T10:00:00Z","tzOffsetMinutes":60}""") as Request.SetDateTime
        assertThat(r.utc).isEqualTo(Instant.parse("2026-11-18T10:00:00Z"))
        assertThat(r.tzOffsetMinutes).isEqualTo(60)
    }

    @Test fun readHistory() {
        assertThat(parse("readHistory", """{"sinceSequence":null,"max":50}""")).isEqualTo(Request.ReadHistory(null, 50))
        assertThat(parse("readHistory", """{"sinceSequence":9876,"max":200}""")).isEqualTo(Request.ReadHistory(9876, 200))
        assertThat(parse("readHistory", """{"max":10}""")).isEqualTo(Request.ReadHistory(null, 10))
    }

    @Test fun confirmAndSnoozeAlert() {
        assertThat(parse("confirmAlert", """{"alertId":3,"alertType":"WARNING_31"}""")).isEqualTo(Request.ConfirmAlert(3, "WARNING_31"))
        assertThat(parse("snoozeAlert", """{"alertId":3,"alertType":"WARNING_31"}""")).isEqualTo(Request.SnoozeAlert(3, "WARNING_31"))
    }

    @Test fun unknownFieldsAreIgnored() {
        assertThat(parse("cancelBolus", """{"bolusId":1,"future":true}""")).isEqualTo(Request.CancelBolus(1))
    }

    @Test fun malformedArgumentsAreInvalidArgs() {
        invalid("deliverBolus", """{"amount":"1.0","requestId":"$uuid"}""")           // number as string
        invalid("deliverBolus", """{"amount":1.0}""")                                  // missing requestId
        invalid("deliverBolus", """{"amount":1.0,"requestId":"not-a-uuid"}""")
        invalid("deliverBolus", """{"amount":null,"requestId":"$uuid"}""")
        invalid("setTBR", """{"percent":120.5,"duration":60,"requestId":"$uuid"}""")  // fractional int
        invalid("setTBR", """{"percent":"120","duration":60,"requestId":"$uuid"}""")
        invalid("setTBR", """{"percent":99999999999,"duration":60,"requestId":"$uuid"}""")
        invalid("cancelBolus", """{}""")
        invalid("setDateTime", """{"utc":"yesterday","tzOffsetMinutes":0}""")
        invalid("setBasalProfile", """{"segments":"x","requestId":"$uuid"}""")
        invalid("setBasalProfile", """{"segments":[{"duration":60}],"requestId":"$uuid"}""")
        invalid("hello", """{"protocol":"1"}""")
        invalid("readHistory", """{"sinceSequence":99999999999999999999,"max":10}""") // beyond 64 bit
        invalid("nope", "{}")
    }

    @Test fun nonObjectFramesAreUnparseable() {
        assertThrows<UnparseableFrame> { RequestParser.parseObject("[]".toByteArray()) }
        assertThrows<UnparseableFrame> { RequestParser.parseObject("not json".toByteArray()) }
    }

    @Test fun idMustBeUint32() {
        fun id(s: String) = RequestParser.idOf(RequestParser.parseObject("""{"id":$s}""".toByteArray()))
        assertThat(id("0")).isEqualTo(0L)
        assertThat(id("4294967295")).isEqualTo(4294967295L)
        assertThat(id("4294967296")).isNull()
        assertThat(id("-1")).isNull()
        assertThat(id("1.5")).isNull()
        assertThat(id("\"1\"")).isNull()
    }

    // ---------------- responses, results, events ----------------

    private fun roundTrip(o: JsonObject) = Json.parseToJsonElement(String(Wire.encode(o))).jsonObject

    @Test fun okAndErrorEnvelopes() {
        val ok = roundTrip(Wire.ok(7, Wire.bolusResult(12, BigDecimal("2.50"))))
        assertThat(ok["id"]!!.jsonPrimitive.int).isEqualTo(7)
        assertThat(ok["ok"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(ok["result"]!!.jsonObject["bolusId"]!!.jsonPrimitive.int).isEqualTo(12)
        assertThat(ok["result"]!!.jsonObject["amount"]!!.jsonPrimitive.content.toDouble()).isEqualTo(2.5)

        val err = roundTrip(Wire.error(8, ErrorCode.PUMP_UNREACHABLE, "lost", true)).also { }
        val e = err["error"]!!.jsonObject
        assertThat(e["code"]!!.jsonPrimitive.content).isEqualTo("pumpUnreachable")
        assertThat(e["message"]!!.jsonPrimitive.content).isEqualTo("lost")
        assertThat(e["uncertain"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test fun everyErrorCodeHasItsSpecName() {
        val spec = listOf("notPaired", "pumpUnreachable", "pumpSuspended", "pumpBusy", "invalidArgs", "limitExceeded", "verifyFailed",
            "alertActive", "busy", "tooLarge", "unsupportedProtocol", "internal")
        assertThat(ErrorCode.entries.map { it.wire }).containsExactlyElementsIn(spec)
        spec.forEach { assertThat(ErrorCode.fromWire(it)?.wire).isEqualTo(it) }
    }

    @Test fun uncertainOnlyAllowedForUnreachableOrInternal() {
        assertThrows<IllegalArgumentException> { PumpOpException(ErrorCode.INVALID_ARGS, "x", uncertain = true) }
        PumpOpException(ErrorCode.PUMP_UNREACHABLE, "x", uncertain = true)
    }

    @Test fun allResults() {
        assertThat(roundTrip(Wire.helloResult("1.0.0", true))["protocol"]!!.jsonPrimitive.int).isEqualTo(2)
        assertThat(Wire.outcomeResult("unknown", null, null, null, false, null).toString()).isEqualTo("""{"state":"unknown","result":null,"error":null,"completedAt":null}""")
        assertThat(Wire.outcomeResult("failed", null, ErrorCode.PUMP_BUSY, "busy", false, "2026-11-18T10:00:00Z").toString())
            .isEqualTo("""{"state":"failed","result":null,"error":{"code":"pumpBusy","message":"busy","uncertain":false},"completedAt":"2026-11-18T10:00:00Z"}""")
        assertThat(Wire.cancelBolusResult(null)["delivered"]).isEqualTo(JsonNull)
        assertThat(Wire.cancelBolusResult(0.45)["delivered"]!!.jsonPrimitive.content.toDouble()).isEqualTo(0.45)
        assertThat(Wire.tbrResult(120, 60).toString()).isEqualTo("""{"percent":120,"duration":60}""")
        assertThat(Wire.emptyResult().toString()).isEqualTo("{}")
        assertThat(Wire.modeResult(true).toString()).isEqualTo("""{"mode":"started"}""")
        assertThat(Wire.modeResult(false).toString()).isEqualTo("""{"mode":"stopped"}""")
        assertThat(Wire.profileResult(1).toString()).isEqualTo("""{"activeProfile":1}""")
        assertThat(Wire.pairingStatusResult(true, "123", false).toString()).isEqualTo("""{"paired":true,"serial":"123","bridgeBluetoothOn":false}""")
        assertThat(Wire.pairingStatusResult(false, null, true).toString()).isEqualTo("""{"paired":false,"serial":null,"bridgeBluetoothOn":true}""")
    }

    private val alert = AlertDto(3, "WARNING_31", "warning", "active", null, 12.5)
    private val status = StatusDto(
        "2026-11-18T10:00:00Z", "connected", "SN1", "fw", "started", BatteryDto(80), ReservoirDto(123.45, "prefilled"),
        BasalDto(1, 0.85), TbrDto(120, 60, 42, "2026-11-18T09:18:00Z"), listOf(ActiveBolusDto(12, "standard", 2.0, 0.6)),
        PumpTimeDto("2026-11-18T10:00:03Z", "2026-11-18T11:00:03", 3), LimitsDto(10.0, 2.5, 0.05, 0.01, 0.02), alert, 9876
    )

    @Test fun statusRoundTripsAndAlwaysContainsEveryField() {
        val json = Wire.toJson(status).jsonObject
        val fields = listOf("timestamp", "pumpLink", "serial", "firmware", "operatingMode", "battery", "reservoir", "basal", "tbr", "boluses",
            "pumpTime", "limits", "activeAlert", "lastHistorySequence")
        assertThat(json.keys).containsExactlyElementsIn(fields)
        assertThat(Wire.json.decodeFromJsonElement(StatusDto.serializer(), json)).isEqualTo(status)
    }

    @Test fun unknownStatusValuesAreExplicitNulls() {
        val empty = StatusDto(null, "notPaired", null, null, null, null, null, null, null, null, null, null, null, null)
        val json = Wire.toJson(empty).jsonObject
        assertThat(json["tbr"]).isEqualTo(JsonNull)
        assertThat(json["timestamp"]).isEqualTo(JsonNull)
        assertThat(json).hasSize(14)
    }

    @Test fun alertShape() {
        val j = Wire.toJson(alert).jsonObject
        assertThat(j.keys).containsExactly("alertId", "alertType", "category", "status", "tbrPercent", "remainingInsulin")
        assertThat(j["tbrPercent"]).isEqualTo(JsonNull)
    }

    @Test fun historyEventsAndPage() {
        val e = HistoryEventDto(9876, "2026-11-18T10:00:00Z", "2026-11-18T11:00:00", "tbrStart",
            buildJsonObject { put("percent", 120); put("duration", 60) })
        val page = roundTrip(Wire.historyResult(HistoryPage(listOf(e), 9876, true)))
        assertThat(page["more"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(page["lastSequence"]!!.jsonPrimitive.content).isEqualTo("9876")
        val ev = (page["events"] as JsonArray)[0].jsonObject
        assertThat(ev.keys).containsAtLeast("seq", "time", "type", "data")
        assertThat(ev["data"]!!.jsonObject["percent"]!!.jsonPrimitive.int).isEqualTo(120)
    }

    @Test fun allEvents() {
        val s = roundTrip(Wire.statusEvent(status))
        assertThat(s["evt"]!!.jsonPrimitive.content).isEqualTo("status")
        assertThat(s["data"]!!.jsonObject["serial"]!!.jsonPrimitive.content).isEqualTo("SN1")
        assertThat(roundTrip(Wire.alertEvent(alert))["evt"]!!.jsonPrimitive.content).isEqualTo("alert")
        val p = roundTrip(Wire.bolusProgressEvent(BolusProgressDto(12, 1.4, 0.6)))
        assertThat(p["evt"]!!.jsonPrimitive.content).isEqualTo("bolusProgress")
        assertThat(p["data"]!!.jsonObject.keys).containsExactly("bolusId", "delivered", "remaining")
        for (state in PumpLinkState.entries) {
            val l = roundTrip(Wire.pumpLinkEvent(state))
            assertThat(l["evt"]!!.jsonPrimitive.content).isEqualTo("pumpLink")
            assertThat(l["data"]!!.jsonObject["state"]!!.jsonPrimitive.content).isEqualTo(state.wire)
        }
        assertThat(PumpLinkState.entries.map { it.wire }).containsExactly("connected", "connecting", "disconnected", "notPaired")
    }

    // ---------------- handshake ----------------

    @Test fun handshakeMessagesParse() {
        val pub = EcdhP256.generate().publicKey
        val n = ByteArray(16) { it.toByte() }
        val commit = PairingCrypto.commitment(pub, n)
        val ps = HandshakeCodec.parse(HandshakeCodec.pairStart(commit, "0123456789abcdef")) as Handshake.PairStart
        assertThat(ps.commit).isEqualTo(commit); assertThat(ps.version).isEqualTo(2); assertThat(ps.clientId).isEqualTo("0123456789abcdef")
        val rv = HandshakeCodec.parse(HandshakeCodec.pairReveal(pub, n)) as Handshake.PairReveal
        assertThat(rv.pub).isEqualTo(pub); assertThat(rv.nonce).isEqualTo(n)
        val pc = HandshakeCodec.parse(HandshakeCodec.pairConfirm(ByteArray(32))) as Handshake.PairConfirm
        assertThat(pc.mac).hasLength(32)
        val ss = HandshakeCodec.parse(HandshakeCodec.sessionStart("0123456789ABCDEF", n)) as Handshake.SessionStart
        assertThat(ss.clientId).isEqualTo("0123456789abcdef")
        assertThat(HandshakeCodec.parse("""{"t":"pairResp"}""".toByteArray())).isInstanceOf(Handshake.Unknown::class.java)
    }

    @Test fun handshakeRejectsBadFields() {
        assertThrows<HandshakeCodec.Invalid> { HandshakeCodec.parse("""{"t":"sessionStart","v":1,"clientId":"zz","nonce":"AAAA"}""".toByteArray()) }
        assertThrows<HandshakeCodec.Invalid> { HandshakeCodec.parse("""{"t":"sessionStart","v":1,"clientId":"0123456789abcdef","nonce":"AAAA"}""".toByteArray()) }
        assertThrows<HandshakeCodec.Invalid> { HandshakeCodec.parse("""{"t":"pairStart","v":2,"commit":"!!"}""".toByteArray()) }
        assertThrows<HandshakeCodec.Invalid> { HandshakeCodec.parse("garbage".toByteArray()) }
    }

    @Test fun handshakeResponsesCarrySpecFields() {
        val resp = Json.parseToJsonElement(String(HandshakeCodec.pairResp(ByteArray(65), ByteArray(16), "00112233445566ff"))).jsonObject
        assertThat(resp.keys).containsExactly("t", "v", "pub", "nonce", "bridgeId")
        assertThat(resp["v"]!!.jsonPrimitive.int).isEqualTo(2)
        val sr = Json.parseToJsonElement(String(HandshakeCodec.sessionResp("00112233445566ff", ByteArray(16), ByteArray(32)))).jsonObject
        assertThat(sr.keys).containsExactly("t", "v", "bridgeId", "nonce", "mac")
        assertThat(String(HandshakeCodec.pairReject("notInPairingMode"))).isEqualTo("""{"t":"pairReject","reason":"notInPairingMode"}""")
        assertThat(String(HandshakeCodec.sessionReject("unknownClient"))).isEqualTo("""{"t":"sessionReject","reason":"unknownClient"}""")
    }

    @Suppress("unused") private val unusedPrim = JsonPrimitive("")
}
