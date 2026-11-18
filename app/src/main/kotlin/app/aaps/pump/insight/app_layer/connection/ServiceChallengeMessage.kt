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
package app.aaps.pump.insight.app_layer.connection

import app.aaps.pump.insight.app_layer.AppLayerMessage
import app.aaps.pump.insight.app_layer.Service
import app.aaps.pump.insight.descriptors.MessagePriority
import app.aaps.pump.insight.utils.ByteBuf

class ServiceChallengeMessage : AppLayerMessage(MessagePriority.NORMAL, false, false, Service.CONNECTION) {

    var serviceID: Byte = 0
    lateinit var randomData: ByteArray
        private set
    var version: Short = 0
    override fun parse(byteBuf: ByteBuf) {
        randomData = byteBuf.getBytes(16)
    }

    override val data: ByteBuf
        get() {
            val byteBuf = ByteBuf(3).apply {
                putByte(serviceID)
                putShort(version)
            }
            return byteBuf
        }
}