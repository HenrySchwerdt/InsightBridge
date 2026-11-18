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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID

/** Opens the RFCOMM socket to the pump on its own thread (connect() blocks for seconds). */
@SuppressLint("MissingPermission") // permissions are checked by the caller before a connection is attempted
class ConnectionEstablisher(
    private val callback: Callback,
    private val forPairing: Boolean,
    private val bluetoothAdapter: BluetoothAdapter,
    private val bluetoothDevice: BluetoothDevice,
    private var socket: BluetoothSocket?
) : Thread("InsightConnect") {

    init {
        isDaemon = true
    }

    override fun run() {
        try {
            if (!bluetoothAdapter.isEnabled) {
                // The bridge never toggles Bluetooth itself (not allowed on modern Android); fail fast.
                if (!isInterrupted) callback.onConnectionFail(IOException("Bluetooth is off"), 0)
                return
            }
            // Discovery slows down or breaks RFCOMM connects.
            if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
            if (forPairing && bluetoothDevice.bondState != BluetoothDevice.BOND_NONE) {
                try {
                    bluetoothDevice.javaClass.getMethod("removeBond").invoke(bluetoothDevice)
                } catch (e: ReflectiveOperationException) {
                    if (!isInterrupted) callback.onConnectionFail(e, 0)
                    return
                }
            }
            try {
                if (socket == null) {
                    socket = bluetoothDevice.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    callback.onSocketCreated(socket)
                }
            } catch (e: IOException) {
                if (!isInterrupted) callback.onConnectionFail(e, 0)
                return
            }
            val connectionStart = System.currentTimeMillis()
            try {
                socket?.connect()
                if (!isInterrupted) callback.onConnectionSucceed()
            } catch (e: IOException) {
                if (!isInterrupted) callback.onConnectionFail(e, System.currentTimeMillis() - connectionStart)
            }
        } catch (t: Throwable) {
            // SecurityException (permission revoked), IllegalStateException, ... must not kill the process.
            PumpLog.e("Connection thread failure", t)
            try {
                if (!isInterrupted) callback.onConnectionFail(IOException("Connection error: ${t.javaClass.simpleName}"), 0)
            } catch (_: Throwable) {
            }
        }
    }

    fun close(closeSocket: Boolean) {
        try {
            interrupt()
            // Close whether or not connect() succeeded: a failed socket is not "connected" but still holds resources.
            if (closeSocket) socket?.close()
        } catch (_: IOException) {
        } catch (_: SecurityException) {
        }
    }

    interface Callback {

        fun onSocketCreated(bluetoothSocket: BluetoothSocket?)
        fun onConnectionSucceed()
        fun onConnectionFail(e: Exception?, duration: Long)
    }

    companion object {

        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
