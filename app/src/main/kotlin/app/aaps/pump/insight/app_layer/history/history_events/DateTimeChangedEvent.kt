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

class DateTimeChangedEvent : HistoryEvent() {

    internal var beforeYear = 0
        private set
    internal var beforeMonth = 0
        private set
    internal var beforeDay = 0
        private set
    internal var beforeHour = 0
        private set
    internal var beforeMinute = 0
        private set
    internal var beforeSecond = 0
        private set

    override fun parse(byteBuf: ByteBuf) {
        byteBuf.let {
            beforeYear = BOCUtil.parseBOC(it.readByte()) * 100 + BOCUtil.parseBOC(it.readByte())
            beforeMonth = BOCUtil.parseBOC(it.readByte())
            beforeDay = BOCUtil.parseBOC(it.readByte())
            it.shift(1)
            beforeHour = BOCUtil.parseBOC(it.readByte())
            beforeMinute = BOCUtil.parseBOC(it.readByte())
            beforeSecond = BOCUtil.parseBOC(it.readByte())
        }
    }
}