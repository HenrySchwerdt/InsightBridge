/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui

import app.aaps.pump.insight.descriptors.AlertType
import app.trio.insightbridge.ui.alerts.AlertCategory
import app.trio.insightbridge.ui.alerts.AlertInfo
import app.trio.insightbridge.ui.util.CodeFormat
import app.trio.insightbridge.ui.util.PhoneFailureKind
import app.trio.insightbridge.ui.util.StatusAge
import app.trio.insightbridge.ui.util.Vendor
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UiLogicTest {

    @Test
    fun `groups a six digit code in threes`() {
        assertThat(CodeFormat.groupSas("123456")).isEqualTo("123 456")
        assertThat(CodeFormat.groupSas("12 34 56")).isEqualTo("123 456")
    }

    @Test
    fun `spoken code reads every character separately`() {
        assertThat(CodeFormat.spoken("ABC DEF GHI J")).isEqualTo("A B C, D E F, G H I, J")
        assertThat(CodeFormat.spoken("123 456")).isEqualTo("1 2 3, 4 5 6")
    }

    @Test
    fun `status older than 15 minutes is stale and unknown age is stale`() {
        val now = 10_000_000L
        assertThat(StatusAge.isStale(now, now - 14 * 60_000L)).isFalse()
        assertThat(StatusAge.isStale(now, now - 16 * 60_000L)).isTrue()
        assertThat(StatusAge.isStale(now, null)).isTrue()
    }

    @Test
    fun `parses iso timestamps and rejects garbage`() {
        assertThat(StatusAge.parse("2026-01-01T00:00:00Z")).isEqualTo(1767225600000L)
        assertThat(StatusAge.parse("nonsense")).isNull()
        assertThat(StatusAge.parse(null)).isNull()
    }

    @Test
    fun `detects vendors from manufacturer and brand`() {
        assertThat(Vendor.detect("samsung")).isEqualTo(Vendor.SAMSUNG)
        assertThat(Vendor.detect("Xiaomi", "POCO")).isEqualTo(Vendor.XIAOMI)
        assertThat(Vendor.detect("HONOR")).isEqualTo(Vendor.HUAWEI)
        assertThat(Vendor.detect("OnePlus")).isEqualTo(Vendor.OPPO)
        assertThat(Vendor.detect("Google", "google")).isEqualTo(Vendor.OTHER)
    }

    @Test
    fun `maps phone pairing failure reasons`() {
        assertThat(PhoneFailureKind.of("timeout")).isEqualTo(PhoneFailureKind.TIMEOUT)
        assertThat(PhoneFailureKind.of("rejected")).isEqualTo(PhoneFailureKind.MISMATCH_OR_REJECTED)
        assertThat(PhoneFailureKind.of("mismatch")).isEqualTo(PhoneFailureKind.MISMATCH_OR_REJECTED)
        assertThat(PhoneFailureKind.of("storage")).isEqualTo(PhoneFailureKind.STORAGE)
        assertThat(PhoneFailureKind.of("???")).isEqualTo(PhoneFailureKind.OTHER)
    }

    @Test
    fun `every pump alert type has its own info and unknown types fall back`() {
        val names = AlertType.entries.map { it.name }.toSet()
        assertThat(AlertInfo.knownTypes).isEqualTo(names)
        names.forEach { assertThat(AlertInfo.of(it).known).isTrue() }
        assertThat(AlertInfo.of("SOMETHING_NEW").known).isFalse()
        assertThat(AlertInfo.of("ERROR_6").category).isEqualTo(AlertCategory.ERROR)
        assertThat(AlertInfo.of("MAINTENANCE_24").category).isEqualTo(AlertCategory.MAINTENANCE)
        assertThat(AlertInfo.of("REMINDER_01").category).isEqualTo(AlertCategory.REMINDER)
    }
}
