/*
 * This file is part of InsightBridge and is derived from the Accu-Chek Insight driver of AndroidAPS
 * (https://github.com/nightscout/AndroidAPS, module pump/insight).
 * Copyright (C) AndroidAPS contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, version 3 or any
 * later version. It is distributed WITHOUT ANY WARRANTY. See the LICENSE file in the repository root.
 *
 * Changes for InsightBridge: AndroidAPS infrastructure (dependency injection, database, rx bus,
 * preferences, plugin framework, UI) removed; plain Kotlin/coroutines, Android Keystore-wrapped storage
 * and robustness fixes added where noted in the code.
 */
package app.aaps.pump.insight.descriptors

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Covers [AlertType.Companion]: fromId / fromIncId lookups (hit + miss). */
class AlertTypeTest {

    @Test
    fun fromId_findsKnownAndNullForUnknown() {
        assertThat(AlertType.fromId(31)).isEqualTo(AlertType.REMINDER_01)
        assertThat(AlertType.fromId(7567)).isEqualTo(AlertType.ERROR_13)
        assertThat(AlertType.fromId(-1)).isNull()
    }

    @Test
    fun fromIncId_findsKnownAndNullForUnknown() {
        assertThat(AlertType.fromIncId(1)).isEqualTo(AlertType.REMINDER_01)
        assertThat(AlertType.fromIncId(13)).isEqualTo(AlertType.ERROR_13)
        assertThat(AlertType.fromIncId(999)).isNull()
    }
}
