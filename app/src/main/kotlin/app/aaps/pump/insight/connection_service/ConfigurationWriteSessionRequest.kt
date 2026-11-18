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

import app.aaps.pump.insight.app_layer.configuration.CloseConfigurationWriteSessionMessage
import app.aaps.pump.insight.app_layer.configuration.OpenConfigurationWriteSessionMessage
import app.aaps.pump.insight.app_layer.configuration.WriteConfigurationBlockMessage

/**
 * Several configuration blocks written inside ONE write session.
 *
 * The pump only applies a write session when it is closed, so grouping the writes means a
 * connection lost part way through leaves the pump exactly as it was, instead of applying the
 * blocks that happened to be written before the link died.
 */
class ConfigurationWriteSessionRequest(
    private val openRequest: MessageRequest<OpenConfigurationWriteSessionMessage>,
    private val writeRequests: List<MessageRequest<WriteConfigurationBlockMessage>>,
    private val closeRequest: MessageRequest<CloseConfigurationWriteSessionMessage>
) {

    /** True if any message of the session was transmitted to the pump. */
    val transmitted: Boolean
        get() = openRequest.transmitted || writeRequests.any { it.transmitted } || closeRequest.transmitted

    @Throws(Exception::class)
    fun await() {
        openRequest.await()
        writeRequests.forEach { it.await() }
        closeRequest.await()
    }

    @Throws(Exception::class)
    fun await(timeout: Long) {
        val deadline = System.currentTimeMillis() + timeout
        fun left() = maxOf(1, deadline - System.currentTimeMillis())
        openRequest.await(left())
        writeRequests.forEach { it.await(left()) }
        closeRequest.await(left())
    }
}
