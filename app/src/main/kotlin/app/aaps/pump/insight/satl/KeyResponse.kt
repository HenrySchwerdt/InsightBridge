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

import app.aaps.pump.insight.utils.ByteBuf

class KeyResponse : SatlMessage() {

    lateinit var randomData: ByteArray
        private set
    lateinit var preMasterSecret: ByteArray
        private set

    override fun parse(byteBuf: ByteBuf) {
        randomData = byteBuf.readBytes(28)
        byteBuf.shift(4)
        preMasterSecret = byteBuf.getBytes(256)
    }
}