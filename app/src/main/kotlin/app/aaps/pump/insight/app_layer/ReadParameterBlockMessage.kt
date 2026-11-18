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
package app.aaps.pump.insight.app_layer

import app.aaps.pump.insight.app_layer.parameter_blocks.ParameterBlock
import app.aaps.pump.insight.descriptors.MessagePriority
import app.aaps.pump.insight.descriptors.ParameterBlocks
import app.aaps.pump.insight.utils.ByteBuf

class ReadParameterBlockMessage : AppLayerMessage(MessagePriority.NORMAL, true, false, null) {

    var parameterBlockId: Class<out ParameterBlock>? = null

    var parameterBlock: ParameterBlock? = null
        private set

    override val data: ByteBuf
        get() {
            val byteBuf = ByteBuf(2)
            ParameterBlocks.fromType(parameterBlockId!!)?.let { byteBuf.putUInt16LE(it.id) }
            return byteBuf
        }

    @Throws(Exception::class) override fun parse(byteBuf: ByteBuf) {
        parameterBlock = ParameterBlocks.fromId(byteBuf.readUInt16LE())?.type?.getDeclaredConstructor()?.newInstance()
        parameterBlock?.let {
            byteBuf.shift(2) //Restriction level
            it.parse(byteBuf)
        }
    }

}