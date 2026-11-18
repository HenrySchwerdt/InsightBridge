/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.util

import java.time.Instant

/** Pure formatting helpers for codes (unit tested). */
object CodeFormat {

    /** "123456" -> "123 456". Non-digits are dropped; any length is grouped in threes. */
    fun groupSas(raw: String): String = raw.filter { it.isDigit() }.chunked(3).joinToString(" ")

    /**
     * Text for TalkBack: every character on its own, groups separated by commas, e.g.
     * "ABC DEF" -> "A B C, D E F". Pauses make the groups audible.
     */
    fun spoken(raw: String): String =
        raw.trim().split(' ').filter { it.isNotEmpty() }.joinToString(", ") { g -> g.toList().joinToString(" ") }
}

/** Age / staleness of pump data. */
object StatusAge {

    const val STALE_AFTER_MS = 15 * 60_000L

    /** Parses the ISO-8601 timestamp of a status; null if missing or malformed. */
    fun parse(timestamp: String?): Long? =
        timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    fun isStale(nowMs: Long, thenMs: Long?): Boolean = thenMs == null || nowMs - thenMs > STALE_AFTER_MS
}

/** Phone manufacturers with their own background-killing settings. */
enum class Vendor {

    SAMSUNG, XIAOMI, HUAWEI, OPPO, VIVO, ASUS, OTHER;

    companion object {

        fun detect(manufacturer: String?, brand: String? = null): Vendor {
            val s = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
            return when {
                "samsung" in s -> SAMSUNG
                "xiaomi" in s || "redmi" in s || "poco" in s -> XIAOMI
                "huawei" in s || "honor" in s -> HUAWEI
                "oppo" in s || "oneplus" in s || "realme" in s -> OPPO
                "vivo" in s || "iqoo" in s -> VIVO
                "asus" in s -> ASUS
                else -> OTHER
            }
        }
    }
}

/** What the iPhone-pairing failure reasons of the core map to. */
enum class PhoneFailureKind { TIMEOUT, MISMATCH_OR_REJECTED, STORAGE, OTHER;

    companion object {

        fun of(reason: String): PhoneFailureKind = when (reason) {
            "timeout" -> TIMEOUT
            "rejected", "mismatch" -> MISMATCH_OR_REJECTED
            "storage" -> STORAGE
            else -> OTHER
        }
    }
}
