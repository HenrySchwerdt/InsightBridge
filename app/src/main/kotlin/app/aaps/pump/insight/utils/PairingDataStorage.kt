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

import app.aaps.pump.insight.descriptors.FirmwareVersions
import app.aaps.pump.insight.descriptors.SystemIdentification
import app.trio.insightbridge.store.KeyValueStore
import org.spongycastle.util.encoders.Hex

/**
 * Pump pairing data (Sight keys, nonces, MAC address). Ported from AndroidAPS; the backing store
 * is now an encrypted [KeyValueStore] (Android Keystore wrapped) instead of plain SharedPreferences.
 * Nonce updates are written synchronously: reusing a nonce after a crash would make the pump reject us.
 */
class PairingDataStorage(private val kv: KeyValueStore) {

    private var _paired: Boolean = kv.getBoolean("paired", false)
    private var _macAddress: String? = kv.getString("macAddress")
    private var _lastNonceSent: Nonce? = kv.getString("lastNonceSent")?.let { Nonce(Hex.decode(it)) }
    private var _lastNonceReceived: Nonce? = kv.getString("lastNonceReceived")?.let { Nonce(Hex.decode(it)) }
    private var _commId: Long = kv.getLong("commId", 0)
    private var _incomingKey: ByteArray? = kv.getString("incomingKey")?.let { Hex.decode(it) }
    private var _outgoingKey: ByteArray? = kv.getString("outgoingKey")?.let { Hex.decode(it) }
    private var _firmwareVersions: FirmwareVersions? = loadFirmware()
    private var _systemIdentification: SystemIdentification? = loadSystemId()

    private fun loadSystemId(): SystemIdentification? {
        val serial = kv.getString("pumpSerial") ?: return null
        return SystemIdentification().also {
            it.serialNumber = serial
            it.manufacturingDate = kv.getString("manufacturingDate")
            it.systemIdAppendix = kv.getLong("systemIdAppendix", 0)
        }
    }

    private fun loadFirmware(): FirmwareVersions? {
        val release = kv.getString("releaseSWVersion") ?: return null
        return FirmwareVersions().also {
            it.releaseSWVersion = release
            it.uiProcSWVersion = kv.getString("uiProcSWVersion")
            it.pcProcSWVersion = kv.getString("pcProcSWVersion")
            it.mdTelProcSWVersion = kv.getString("mdTelProcSWVersion")
            it.btInfoPageVersion = kv.getString("btInfoPageVersion")
            it.safetyProcSWVersion = kv.getString("safetyProcSWVersion")
            it.configIndex = kv.getLong("configIndex", 0).toInt()
            it.historyIndex = kv.getLong("historyIndex", 0).toInt()
            it.stateIndex = kv.getLong("stateIndex", 0).toInt()
            it.vocabularyIndex = kv.getLong("vocabularyIndex", 0).toInt()
        }
    }

    private fun hex(b: ByteArray?) = if (b == null) null else Hex.toHexString(b)

    @get:Synchronized
    @set:Synchronized
    var paired: Boolean
        get() = _paired
        set(value) {
            _paired = value
            kv.putBoolean("paired", value)
        }

    @get:Synchronized
    @set:Synchronized
    var macAddress: String?
        get() = _macAddress
        set(value) {
            _macAddress = value
            kv.putString("macAddress", value)
        }

    @get:Synchronized
    @set:Synchronized
    var lastNonceSent: Nonce?
        get() = _lastNonceSent
        set(value) {
            _lastNonceSent = value
            kv.putString("lastNonceSent", hex(value?.storageValue))
        }

    @get:Synchronized
    @set:Synchronized
    var lastNonceReceived: Nonce?
        get() = _lastNonceReceived
        set(value) {
            _lastNonceReceived = value
            kv.putString("lastNonceReceived", hex(value?.storageValue))
        }

    @get:Synchronized
    @set:Synchronized
    var commId: Long
        get() = _commId
        set(value) {
            _commId = value
            kv.putLong("commId", value)
        }

    @get:Synchronized
    @set:Synchronized
    var incomingKey: ByteArray?
        get() = _incomingKey
        set(value) {
            _incomingKey = value
            kv.putString("incomingKey", hex(value))
        }

    @get:Synchronized
    @set:Synchronized
    var outgoingKey: ByteArray?
        get() = _outgoingKey
        set(value) {
            _outgoingKey = value
            kv.putString("outgoingKey", hex(value))
        }

    @get:Synchronized
    @set:Synchronized
    var firmwareVersions: FirmwareVersions?
        get() = _firmwareVersions
        set(v) {
            _firmwareVersions = v
            kv.edit(
                mapOf(
                    "releaseSWVersion" to v?.releaseSWVersion,
                    "uiProcSWVersion" to v?.uiProcSWVersion,
                    "pcProcSWVersion" to v?.pcProcSWVersion,
                    "mdTelProcSWVersion" to v?.mdTelProcSWVersion,
                    "btInfoPageVersion" to v?.btInfoPageVersion,
                    "safetyProcSWVersion" to v?.safetyProcSWVersion,
                    "configIndex" to (v?.configIndex ?: 0).toString(),
                    "historyIndex" to (v?.historyIndex ?: 0).toString(),
                    "stateIndex" to (v?.stateIndex ?: 0).toString(),
                    "vocabularyIndex" to (v?.vocabularyIndex ?: 0).toString()
                )
            )
        }

    @get:Synchronized
    @set:Synchronized
    var systemIdentification: SystemIdentification?
        get() = _systemIdentification
        set(v) {
            _systemIdentification = v
            kv.edit(
                mapOf(
                    "pumpSerial" to v?.serialNumber,
                    "manufacturingDate" to v?.manufacturingDate,
                    "systemIdAppendix" to (v?.systemIdAppendix ?: 0).toString()
                )
            )
        }

    @Synchronized
    fun reset() {
        _paired = false; _macAddress = null; _commId = 0
        _incomingKey = null; _outgoingKey = null
        _lastNonceReceived = null; _lastNonceSent = null
        _firmwareVersions = null; _systemIdentification = null
        // one write for everything
        kv.edit(
            mapOf(
                "paired" to null, "macAddress" to null, "commId" to null,
                "incomingKey" to null, "outgoingKey" to null,
                "lastNonceSent" to null, "lastNonceReceived" to null,
                "releaseSWVersion" to null, "uiProcSWVersion" to null, "pcProcSWVersion" to null,
                "mdTelProcSWVersion" to null, "btInfoPageVersion" to null, "safetyProcSWVersion" to null,
                "configIndex" to null, "historyIndex" to null, "stateIndex" to null, "vocabularyIndex" to null,
                "pumpSerial" to null, "manufacturingDate" to null, "systemIdAppendix" to null
            )
        )
    }
}
