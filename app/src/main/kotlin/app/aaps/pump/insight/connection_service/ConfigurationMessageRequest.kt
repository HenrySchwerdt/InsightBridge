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
package app.aaps.pump.insight.connection_service

import app.aaps.pump.insight.app_layer.AppLayerMessage
import app.aaps.pump.insight.app_layer.configuration.CloseConfigurationWriteSessionMessage
import app.aaps.pump.insight.app_layer.configuration.OpenConfigurationWriteSessionMessage

class ConfigurationMessageRequest<T : AppLayerMessage>(
    request: T,
    private val openRequest: MessageRequest<OpenConfigurationWriteSessionMessage>,
    private val closeRequest: MessageRequest<CloseConfigurationWriteSessionMessage>
) : MessageRequest<T>(request) {

    @Throws(Exception::class) override fun await(): T {
        openRequest.await()
        val response = super.await()
        closeRequest.await()
        return response
    }

    @Throws(Exception::class) override fun await(timeout: Long): T {
        val deadline = System.currentTimeMillis() + timeout
        openRequest.await(timeout)
        val response = super.await(maxOf(1, deadline - System.currentTimeMillis()))
        closeRequest.await(maxOf(1, deadline - System.currentTimeMillis()))
        return response
    }
}
