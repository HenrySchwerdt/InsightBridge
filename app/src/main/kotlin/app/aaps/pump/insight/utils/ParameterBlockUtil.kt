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
package app.aaps.pump.insight.utils

import app.aaps.pump.insight.app_layer.ReadParameterBlockMessage
import app.aaps.pump.insight.app_layer.Service
import app.aaps.pump.insight.app_layer.configuration.WriteConfigurationBlockMessage
import app.aaps.pump.insight.app_layer.parameter_blocks.ParameterBlock
import app.aaps.pump.insight.connection_service.ConfigurationWriteSessionRequest
import app.aaps.pump.insight.connection_service.InsightConnection

@SuppressWarnings("unchecked")
object ParameterBlockUtil {

    const val DEFAULT_TIMEOUT_MS = 20_000L

    @Suppress("Unchecked_Cast")
    @Throws(Exception::class)
    fun <T : ParameterBlock?> readParameterBlock(
        connection: InsightConnection, service: Service?, parameterBlock: Class<out T>?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): T? {
        val readMessage = ReadParameterBlockMessage()
        readMessage.service = service
        readMessage.parameterBlockId = parameterBlock
        return connection.requestMessage(readMessage).await(timeoutMs).parameterBlock as T?
    }

    @Throws(Exception::class)
    fun writeConfigurationBlock(connection: InsightConnection, parameterBlock: ParameterBlock?, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        val writeMessage = WriteConfigurationBlockMessage()
        writeMessage.setParameterBlock(parameterBlock)
        connection.requestMessage(writeMessage).await(timeoutMs)
    }

    /**
     * Requests several blocks inside ONE write session, in the order given (the pump commits a
     * session when it is closed, so a lost link leaves it unchanged). Returns the pending session
     * so the caller can inspect [ConfigurationWriteSessionRequest.transmitted] if it fails.
     */
    fun requestConfigurationBlocks(connection: InsightConnection, vararg parameterBlocks: ParameterBlock): ConfigurationWriteSessionRequest {
        val messages = parameterBlocks.map { block ->
            WriteConfigurationBlockMessage().apply { setParameterBlock(block) }
        }
        return connection.requestConfigurationWrites(messages)
    }
}
