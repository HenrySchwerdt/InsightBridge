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
package app.aaps.pump.insight.satl

enum class SatlError(val id: Byte) {
    UNDEFINED(0.toByte()),
    INCOMPATIBLE_VERSION(1.toByte()),
    INVALID_COMM_ID(2.toByte()),
    INVALID_MAC_TRAILER(3.toByte()),
    INVALID_CRC(4.toByte()),
    INVALID_PACKET(5.toByte()),
    INVALID_NONCE(6.toByte()),
    DECRYPT_VERIFY_FAILED(7.toByte()),
    COMPATIBLE_STATE(8.toByte()),
    WRONG_STATE(0x0F.toByte()),
    INVALID_MESSAGE_TYPE(51.toByte()),
    INVALID_PAYLOAD_LENGTH(60.toByte()),
    NONE(255.toByte());

    companion object {

        fun fromId(id: Byte) = SatlError.entries.firstOrNull { it.id == id } ?: NONE
    }
}