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
package app.aaps.pump.insight.descriptors

import java.util.Objects

class Alert {

    var alertId = 0
    var alertCategory: AlertCategory? = null
    var alertType: AlertType? = null
    var alertStatus: AlertStatus? = null
    var tBRAmount = 0
    var tBRDuration = 0
    var programmedBolusAmount = 0.0
    var deliveredBolusAmount = 0.0
    var cartridgeAmount = 0.0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val alert = other as Alert
        if (alertId != alert.alertId) return false
        if (tBRAmount != alert.tBRAmount) return false
        if (tBRDuration != alert.tBRDuration) return false
        if (alert.programmedBolusAmount.compareTo(programmedBolusAmount) != 0) return false
        if (alert.deliveredBolusAmount.compareTo(deliveredBolusAmount) != 0) return false
        if (alert.cartridgeAmount.compareTo(cartridgeAmount) != 0) return false
        if (alertCategory !== alert.alertCategory) return false
        return alertType === alert.alertType && alertStatus === alert.alertStatus
    }

    override fun hashCode(): Int {
        return Objects.hash(alertId, alertCategory, alertType, alertStatus, tBRAmount, tBRDuration, programmedBolusAmount, deliveredBolusAmount, cartridgeAmount)
    }
}