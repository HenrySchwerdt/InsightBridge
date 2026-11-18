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

enum class BolusType(val id: Int, val activeId: Int) {
    STANDARD(31, 227),
    EXTENDED(227, 252),
    MULTIWAVE(252, 805);

    companion object {

        fun fromActiveId(activeId: Int) = BolusType.entries.firstOrNull { it.activeId == activeId }
        fun fromId(id: Int) = BolusType.entries.firstOrNull { it.id == id }

    }
}