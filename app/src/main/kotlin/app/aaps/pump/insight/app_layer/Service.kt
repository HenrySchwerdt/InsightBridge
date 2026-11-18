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
package app.aaps.pump.insight.app_layer

enum class Service(val version: Short, var servicePassword: String?, val id: Byte) {
    CONNECTION(0x0000.toShort(), null, 0.toByte()),
    STATUS(0x0100.toShort(), null, 15.toByte()),
    HISTORY(0x0200.toShort(), null, 60.toByte()),
    CONFIGURATION(0x0200.toShort(), "u+5Fhz6Gw4j1Kkas", 85.toByte()),
    PARAMETER(0x0200.toShort(), null, 51.toByte()),
    REMOTE_CONTROL(0x0100.toShort(), "MAbcV2X6PVjxuz+R", 102.toByte());

    companion object {

        fun fromId(id: Byte) = Service.entries.firstOrNull { it.id == id }
    }
}