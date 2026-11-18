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
import app.aaps.pump.insight.exceptions.TimeoutException
import app.aaps.pump.insight.utils.wait
import app.aaps.pump.insight.utils.waitMillis

open class MessageRequest<T : AppLayerMessage> internal constructor(var request: T) : Comparable<MessageRequest<*>> {

    var response: T? = null
        set(value) { field = value; fireCompleted() }
    var exception: Exception? = null
        set(value) { field = value; fireCompleted() }

    private var completionListener: ((T?, Exception?) -> Unit)? = null

    private fun fireCompleted() {
        val l = synchronized(this) { val c = completionListener; if (response != null || exception != null) completionListener = null; c }
        if (l != null && (response != null || exception != null)) l(response, exception)
    }

    /** Calls [listener] once when the request completes (immediately if it already has). Must be quick and non-blocking. */
    fun onCompleted(listener: (T?, Exception?) -> Unit) {
        val done = synchronized(this) {
            if (response != null || exception != null) true else { completionListener = listener; false }
        }
        if (done) listener(response, exception)
    }

    /**
     * True once the message was handed to the Bluetooth writer. If a request fails with a link
     * error after this became true, the caller cannot know whether the pump executed it.
     */
    @Volatile var transmitted: Boolean = false
        internal set

    @Throws(Exception::class)
    open fun await(): T {
        synchronized(this) {
            while (exception == null && response == null) wait()
            exception?.let { e -> throw e }
            return response!!
        }
    }

    /** Waits at most [timeout] ms overall (the AAPS version looped forever on spurious wakeups). */
    @Throws(Exception::class)
    open fun await(timeout: Long): T {
        val deadline = System.currentTimeMillis() + timeout
        synchronized(this) {
            while (exception == null && response == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw TimeoutException()
                waitMillis(left)
            }
            exception?.let { e -> throw e }
            return response!!
        }
    }

    override fun compareTo(other: MessageRequest<*>): Int {
        return request.compareTo(other.request)
    }
}
