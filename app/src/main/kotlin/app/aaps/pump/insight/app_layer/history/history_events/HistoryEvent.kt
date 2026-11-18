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
package app.aaps.pump.insight.app_layer.history.history_events

import app.aaps.pump.insight.descriptors.HistoryEvents.Companion.fromId
import app.aaps.pump.insight.utils.BOCUtil
import app.aaps.pump.insight.utils.ByteBuf

open class HistoryEvent : Comparable<HistoryEvent> {

    var eventYear = 0
        private set
    var eventMonth = 0
        private set
    var eventDay = 0
        private set
    var eventHour = 0
        private set
    var eventMinute = 0
        private set
    var eventSecond = 0
        private set
    var eventPosition: Long = 0
        private set

    fun parseHeader(byteBuf: ByteBuf) {
        byteBuf.let {
            eventYear = BOCUtil.parseBOC(it.readByte()) * 100 + BOCUtil.parseBOC(it.readByte())
            eventMonth = BOCUtil.parseBOC(it.readByte())
            eventDay = BOCUtil.parseBOC(it.readByte())
            it.shift(1)
            eventHour = BOCUtil.parseBOC(it.readByte())
            eventMinute = BOCUtil.parseBOC(it.readByte())
            eventSecond = BOCUtil.parseBOC(it.readByte())
            eventPosition = it.readUInt32LE()
        }
    }

    open fun parse(byteBuf: ByteBuf) = Unit

    override fun compareTo(other: HistoryEvent): Int = (eventPosition - other.eventPosition).toInt()

    companion object {

        fun deserialize(byteBuf: ByteBuf): HistoryEvent = fromId(byteBuf.readUInt16LE()).apply {
            parseHeader(byteBuf)
            parse(byteBuf)
        }
    }
}