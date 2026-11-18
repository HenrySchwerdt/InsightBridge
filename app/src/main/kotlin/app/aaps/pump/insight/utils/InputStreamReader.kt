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

import java.io.IOException
import java.io.InputStream

class InputStreamReader(inputStream: InputStream, callback: Callback) : Thread() {

    private val inputStream: InputStream
    private val callback: Callback
    override fun run() {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            while (!isInterrupted) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) {
                    // End of stream: report once and stop. (AAPS kept looping here.)
                    if (!isInterrupted) callback.onErrorWhileReading(IOException("Stream closed"))
                    break
                }
                callback.onReceiveBytes(buffer, bytesRead)
            }
        } catch (e: IOException) {
            if (!isInterrupted) safeReport(e)
        } catch (t: Throwable) {
            // A bug in a callback must not kill the process; treat it as a broken link.
            PumpLog.e("Reader thread failure", t)
            if (!isInterrupted) safeReport(IOException("Reader failure: ${t.javaClass.simpleName}"))
        } finally {
            try {
                inputStream.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun safeReport(e: Exception) {
        try {
            callback.onErrorWhileReading(e)
        } catch (t: Throwable) {
            PumpLog.e("Reader error callback failed", t)
        }
    }

    fun close() {
        interrupt()
        try {
            inputStream.close()
        } catch (_: IOException) {
        }
    }

    interface Callback {

        fun onReceiveBytes(buffer: ByteArray, bytesRead: Int)
        fun onErrorWhileReading(e: Exception)
    }

    companion object {

        private const val BUFFER_SIZE = 1024
    }

    init {
        name = javaClass.simpleName
        isDaemon = true
        this.inputStream = inputStream
        this.callback = callback
    }
}
