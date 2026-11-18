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
import java.io.OutputStream

/**
 * Writer thread for the pump socket.
 *
 * The blocking `OutputStream.write` runs OUTSIDE the lock that guards the queue. In the AndroidAPS version it
 * ran inside it, so a write blocked on a dead socket held that lock and every caller of [write] / [writeAndWait]
 * (which hold the connection monitor) stalled behind it - the connection could not even time out. Now only
 * short critical sections touch the queue, and [close] (which closes the stream, unblocking a stuck write with an
 * IOException) never needs the lock.
 */
class OutputStreamWriter(outputStream: OutputStream, callback: Callback) : Thread() {

    private val outputStream: OutputStream
    private val callback: Callback
    private val lock = Any()
    private val queue = ByteBuf(BUFFER_SIZE)
    private var queuedTotal = 0L
    private var writtenTotal = 0L

    override fun run() {
        try {
            while (!isInterrupted) {
                val bytes: ByteArray
                val upTo: Long
                synchronized(lock) {
                    while (queue.filledSize == 0) lock.wait()
                    bytes = queue.readBytes()
                    upTo = queuedTotal
                }
                outputStream.write(bytes) // may block: no lock held
                outputStream.flush()
                synchronized(lock) {
                    writtenTotal = upTo
                    lock.notifyAll()
                }
            }
        } catch (e: IOException) {
            if (!isInterrupted) safeReport(e)
        } catch (ignored: InterruptedException) {
        } catch (t: Throwable) {
            PumpLog.e("Writer thread failure", t)
            if (!isInterrupted) safeReport(IOException("Writer failure: ${t.javaClass.simpleName}"))
        } finally {
            try {
                outputStream.close()
            } catch (e: IOException) {
            }
            synchronized(lock) { lock.notifyAll() } // release anybody waiting in writeAndWait
        }
    }

    private fun safeReport(e: Exception) {
        try {
            callback.onErrorWhileWriting(e)
        } catch (t: Throwable) {
            PumpLog.e("Writer error callback failed", t)
        }
    }

    fun write(bytes: ByteArray) {
        synchronized(lock) {
            queue.putBytes(bytes)
            queuedTotal += bytes.size
            lock.notifyAll()
        }
    }

    /** Writes [bytes] and waits until the writer thread has flushed them, at most [WRITE_AND_WAIT_TIMEOUT_MS]. */
    fun writeAndWait(bytes: ByteArray) {
        val deadline = System.currentTimeMillis() + WRITE_AND_WAIT_TIMEOUT_MS
        synchronized(lock) {
            queue.putBytes(bytes)
            queuedTotal += bytes.size
            val target = queuedTotal
            lock.notifyAll()
            try {
                while (writtenTotal < target && isAlive) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    lock.waitMillis(left)
                }
            } catch (e: InterruptedException) {
                currentThread().interrupt()
            }
        }
    }

    fun close() {
        interrupt()
        try {
            outputStream.close()
        } catch (e: IOException) {
        }
    }

    interface Callback {

        fun onErrorWhileWriting(e: Exception)
    }

    companion object {

        private const val BUFFER_SIZE = 4096
        const val WRITE_AND_WAIT_TIMEOUT_MS = 2000L
    }

    init {
        name = javaClass.simpleName
        isDaemon = true
        this.outputStream = outputStream
        this.callback = callback
    }
}
