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
package app.aaps.pump.insight.exceptions

/**
 * The Bluetooth socket could not be connected.
 *
 * [cause] is the IOException the platform raised. It used to be dropped, which left every
 * connection failure looking the same in the log - "out of range", "socket already used" and
 * "refused" are very different problems and only the platform message tells them apart.
 */
class ConnectionFailedException(val durationOfConnectionAttempt: Long, cause: Throwable? = null) : InsightException(cause)
