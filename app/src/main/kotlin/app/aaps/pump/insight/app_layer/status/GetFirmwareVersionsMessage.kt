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
package app.aaps.pump.insight.app_layer.status

import app.aaps.pump.insight.app_layer.AppLayerMessage
import app.aaps.pump.insight.app_layer.Service
import app.aaps.pump.insight.descriptors.FirmwareVersions
import app.aaps.pump.insight.descriptors.MessagePriority
import app.aaps.pump.insight.utils.ByteBuf

class GetFirmwareVersionsMessage : AppLayerMessage(MessagePriority.NORMAL, false, false, Service.STATUS) {

    var firmwareVersions: FirmwareVersions? = null
        private set

    override fun parse(byteBuf: ByteBuf) {
        firmwareVersions = FirmwareVersions().apply {
            byteBuf.let {
                releaseSWVersion = it.readASCII(13)
                uiProcSWVersion = it.readASCII(11)
                pcProcSWVersion = it.readASCII(11)
                mdTelProcSWVersion = it.readASCII(11)
                btInfoPageVersion = it.readASCII(11)
                safetyProcSWVersion = it.readASCII(11)
                configIndex = it.readUInt16LE()
                historyIndex = it.readUInt16LE()
                stateIndex = it.readUInt16LE()
                vocabularyIndex = it.readUInt16LE()
            }
        }
    }
}