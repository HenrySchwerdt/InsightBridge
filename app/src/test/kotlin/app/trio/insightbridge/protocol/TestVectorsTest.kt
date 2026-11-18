/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.protocol

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

/**
 * `InsightBridge/test-vectors.json` is generated from fixed inputs by [TestVectorBuilder]. This test fails if
 * the committed file and the implementation ever disagree. Regenerate deliberately with
 * `GEN_VECTORS=1 ./gradlew testDebugUnitTest --tests '*TestVectorsTest*'`.
 */
class TestVectorsTest {

    private val file = File("../test-vectors.json")

    @Test
    fun committedVectorsMatchTheImplementation() {
        val expected = TestVectorBuilder.build()
        if (System.getenv("GEN_VECTORS") == "1") file.writeText(TestVectorBuilder.prettyPrint(expected))
        assertThat(file.exists()).isTrue()
        val actual = Json.parseToJsonElement(file.readText()) as JsonObject
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun vectorsAreSelfConsistent() {
        val v = Json.parseToJsonElement(file.readText()) as JsonObject
        fun str(o: JsonObject, k: String) = (o[k] as kotlinx.serialization.json.JsonPrimitive).content
        val pairing = v["pairing"] as JsonObject
        val ltk = TestVectorBuilder.unhex(str(pairing, "ltk"))
        // an envelope from the file opens with the session key from the file
        val session = v["session"] as JsonObject
        val key = TestVectorBuilder.unhex(str(session, "sessionKey"))
        val envs = v["envelopes"] as kotlinx.serialization.json.JsonArray
        for (e in envs) {
            val o = e as JsonObject
            val dir = (o["directionByte"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
            val plain = Envelope.open(key, dir, TestVectorBuilder.unhex(str(o, "envelope")))
            assertThat(String(plain)).isEqualTo(str(o, "plaintextUtf8"))
        }
        assertThat(PairingCrypto.sas(ltk)).isEqualTo(str(pairing, "sas"))
        // fragments reassemble to the message
        val frag = v["fragmentation"] as JsonObject
        val r = Reassembler { 0L }
        var result: ReassemblyResult = ReassemblyResult.Incomplete
        (frag["fragments"] as kotlinx.serialization.json.JsonArray).forEach {
            result = r.feed(TestVectorBuilder.unhex((it as kotlinx.serialization.json.JsonPrimitive).content))
        }
        assertThat(TestVectorBuilder.hex((result as ReassemblyResult.Message).bytes)).isEqualTo(str(frag, "message"))
    }
}
