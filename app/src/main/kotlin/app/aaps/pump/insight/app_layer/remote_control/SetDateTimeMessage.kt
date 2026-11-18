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
package app.aaps.pump.insight.app_layer.remote_control

import app.aaps.pump.insight.app_layer.AppLayerMessage
import app.aaps.pump.insight.app_layer.Service
import app.aaps.pump.insight.descriptors.MessagePriority
import app.aaps.pump.insight.descriptors.PumpTime
import app.aaps.pump.insight.utils.ByteBuf

class SetDateTimeMessage : AppLayerMessage(MessagePriority.NORMAL, false, true, Service.CONFIGURATION) {

    internal lateinit var pumpTime: PumpTime
    override val data: ByteBuf
        get() {
            val byteBuf = ByteBuf(7).apply {
                putUInt16LE(pumpTime.year)
                putUInt8(pumpTime.month.toShort())
                putUInt8(pumpTime.day.toShort())
                putUInt8(pumpTime.hour.toShort())
                putUInt8(pumpTime.minute.toShort())
                putUInt8(pumpTime.second.toShort())
            }
            return byteBuf
        }
}