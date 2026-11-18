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

import java.math.BigInteger

class Nonce {

    private var bigInteger: BigInteger

    constructor() {
        bigInteger = BigInteger.ZERO
    }

    constructor(storageValue: ByteArray) {
        bigInteger = BigInteger(storageValue)
    }

    val storageValue: ByteArray
        get() = bigInteger.toByteArray()
    val productionalBytes: ByteBuf
        get() {
            val byteBuf = ByteBuf(13)
            byteBuf.putBytesLE(bigInteger.toByteArray())
            byteBuf.putBytes(0x00.toByte(), 13 - byteBuf.filledSize)
            return byteBuf
        }

    fun increment() {
        bigInteger = bigInteger.add(BigInteger.ONE)
    }

    fun increment(count: Int) {
        bigInteger = bigInteger.add(BigInteger.valueOf(count.toLong()))
    }

    fun isSmallerThan(greater: Nonce): Boolean {
        return bigInteger < greater.bigInteger
    }

    companion object {

        fun fromProductionalBytes(bytes: ByteArray): Nonce {
            val byteBuf = ByteBuf(14)
            byteBuf.putByte(0x00.toByte())
            byteBuf.putBytesLE(bytes)
            return Nonce(byteBuf.bytes)
        }
    }
}