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

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handle for a delayed action. Replaces the thread-per-timer DelayedActionThread of AndroidAPS:
 * the connection arms a response timeout for every message, and a new thread each time is wasteful
 * for a process that has to run for months. All timers share one small daemon pool.
 */
class DelayedActionThread private constructor(private val future: ScheduledFuture<*>) {

    fun interrupt() {
        future.cancel(false)
    }

    companion object {

        private val counter = AtomicInteger()
        private val scheduler = ScheduledThreadPoolExecutor(2, ThreadFactory { r ->
            Thread(r, "insight-timer-" + counter.incrementAndGet()).apply { isDaemon = true }
        }).apply { removeOnCancelPolicy = true }

        fun runDelayed(name: String, duration: Long, runnable: Runnable): DelayedActionThread {
            val future = scheduler.schedule({
                try {
                    runnable.run()
                } catch (t: Throwable) {
                    PumpLog.e("Timer '$name' failed", t)
                }
            }, duration, TimeUnit.MILLISECONDS)
            return DelayedActionThread(future)
        }
    }
}
