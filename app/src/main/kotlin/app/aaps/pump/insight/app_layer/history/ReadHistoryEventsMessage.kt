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
package app.aaps.pump.insight.app_layer.history

import app.aaps.pump.insight.app_layer.AppLayerMessage
import app.aaps.pump.insight.app_layer.Service
import app.aaps.pump.insight.app_layer.history.history_events.HistoryEvent
import app.aaps.pump.insight.descriptors.MessagePriority
import app.aaps.pump.insight.utils.ByteBuf

class ReadHistoryEventsMessage : AppLayerMessage(MessagePriority.NORMAL, true, false, Service.HISTORY) {

    internal var historyEvents: MutableList<HistoryEvent> = mutableListOf()

    override fun parse(byteBuf: ByteBuf) {
        historyEvents = mutableListOf()
        historyEvents.let {
            byteBuf.let { it1 ->
                it1.shift(2)
                val frameCount = it1.readUInt16LE()
                for (i in 0 until frameCount) {
                    val length = it1.readUInt16LE()
                    it.add(HistoryEvent.deserialize(ByteBuf.from(it1.readBytes(length))))
                }
            }
        }
    }
}