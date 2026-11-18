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

import app.aaps.pump.insight.utils.BOCUtil
import app.aaps.pump.insight.utils.ByteBuf

class EndOfTBREvent : HistoryEvent() {

    internal var startHour = 0
        private set
    internal var startMinute = 0
        private set
    internal var startSecond = 0
        private set
    internal var amount = 0
        private set
    internal var duration = 0
        private set

    override fun parse(byteBuf: ByteBuf) {
        byteBuf.let {
            it.shift(1)
            startHour = BOCUtil.parseBOC(it.readByte())
            startMinute = BOCUtil.parseBOC(it.readByte())
            startSecond = BOCUtil.parseBOC(it.readByte())
            amount = it.readUInt16LE()
            duration = it.readUInt16LE()
        }
    }
}