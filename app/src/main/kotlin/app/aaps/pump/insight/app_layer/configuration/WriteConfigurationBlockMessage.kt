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
package app.aaps.pump.insight.app_layer.configuration

import app.aaps.pump.insight.app_layer.AppLayerMessage
import app.aaps.pump.insight.app_layer.Service
import app.aaps.pump.insight.app_layer.parameter_blocks.ParameterBlock
import app.aaps.pump.insight.descriptors.MessagePriority
import app.aaps.pump.insight.descriptors.ParameterBlocks
import app.aaps.pump.insight.utils.ByteBuf

class WriteConfigurationBlockMessage : AppLayerMessage(MessagePriority.NORMAL, false, true, Service.CONFIGURATION) {

    internal var parameterBlock: ParameterBlock? = null
    internal var configurationBlockId: Class<out ParameterBlock?>? = null
        private set

    override val data: ByteBuf
        get() {
            val configBlockData = parameterBlock!!.data
            val data = ByteBuf(4 + configBlockData!!.filledSize).apply {
                putUInt16LE(ParameterBlocks.fromType(parameterBlock!!.javaClass)!!.id)
                putUInt16LE(31)
                putByteBuf(configBlockData)
            }
            return data
        }

    override fun parse(byteBuf: ByteBuf) {
        configurationBlockId = ParameterBlocks.fromId(byteBuf.readUInt16LE())!!.type
    }

    fun setParameterBlock(parameterBlock: ParameterBlock?) {
        this.parameterBlock = parameterBlock
    }
}