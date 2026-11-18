/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.util

import app.aaps.pump.insight.utils.PumpLog
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UtilTest {

    @Test fun logBufferIsBoundedAndKeepsNewest() {
        val log = LogBuffer(capacity = 100)
        repeat(10_000) { log.add(PumpLog.Level.INFO, "line $it") }
        assertThat(log.size).isEqualTo(100)
        assertThat(log.flow.value.last().text).isEqualTo("line 9999")
        assertThat(log.flow.value.first().text).isEqualTo("line 9900")
    }

    @Test fun logBufferTruncatesLongLinesAndPersistsOnlyWarningsAndErrors() {
        val persisted = ArrayList<String>()
        val log = LogBuffer(10).also { it.persistent = { l -> persisted.add(l) } }
        log.add(PumpLog.Level.INFO, "x".repeat(1000))
        log.add(PumpLog.Level.ERROR, "boom")
        assertThat(log.flow.value[0].text.length).isEqualTo(300)
        assertThat(persisted).hasSize(1)
    }

    @Test fun durations() {
        assertThat(Format.duration(12_000)).isEqualTo("12s")
        assertThat(Format.duration(250_000)).isEqualTo("4m 10s")
        assertThat(Format.duration(7_500_000)).isEqualTo("2h 05m")
        assertThat(Format.duration(3 * 86_400_000L + 4 * 3_600_000L)).isEqualTo("3d 4h")
        assertThat(Format.ago(1000, 0)).isEqualTo("never")
        assertThat(Format.ago(100_000, 40_000)).isEqualTo("1m 00s ago")
    }

    @Test fun fileLogRotatesAtSizeCap() {
        val dir = java.nio.file.Files.createTempDirectory("fl").toFile()
        val f = java.io.File(dir, "c.log")
        val log = FileLog(f, maxBytes = 1000)
        repeat(200) { log.append("line number $it") }
        assertThat(f.length()).isAtMost(1100)
        assertThat(java.io.File(f.path + ".1").exists()).isTrue()
        assertThat(log.readTail()).contains("line number 199")
    }
}
