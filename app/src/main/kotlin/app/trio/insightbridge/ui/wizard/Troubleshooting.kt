/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.wizard

import app.trio.insightbridge.R
import app.trio.insightbridge.service.PumpPairFailure
import app.trio.insightbridge.ui.components.TroubleTopic

/** All troubleshooting topics of the wizard; the pump failures map onto them. */
object Troubles {

    val permDenied = TroubleTopic(R.string.ts_perm_denied_t, R.string.ts_perm_denied_b)
    val btOff = TroubleTopic(R.string.ts_bt_off_t, R.string.ts_bt_off_b)
    val batteryScreen = TroubleTopic(R.string.ts_battery_screen_t, R.string.ts_battery_screen_b)
    val vendorMissing = TroubleTopic(R.string.ts_vendor_missing_t, R.string.ts_vendor_missing_b)

    val pumpTimeout = TroubleTopic(R.string.ts_pump_timeout_t, R.string.ts_pump_timeout_b)
    val pumpNotFound = TroubleTopic(R.string.ts_pump_notfound_t, R.string.ts_pump_notfound_b)
    val pumpMismatch = TroubleTopic(R.string.ts_pump_mismatch_t, R.string.ts_pump_mismatch_b)
    val pumpFull = TroubleTopic(R.string.ts_pump_full_t, R.string.ts_pump_full_b)

    val phoneNothing = TroubleTopic(R.string.ts_phone_nothing_t, R.string.ts_phone_nothing_b)
    val phoneTimeout = TroubleTopic(R.string.ts_phone_timeout_t, R.string.ts_phone_timeout_b)
    val phoneMismatch = TroubleTopic(R.string.ts_phone_mismatch_t, R.string.ts_phone_mismatch_b)
    val phoneAgain = TroubleTopic(R.string.ts_phone_again_t, R.string.ts_phone_again_b)

    val permissionsStep = listOf(permDenied, btOff)
    val batteryStep = listOf(batteryScreen, vendorMissing)
    val pumpRemoveStep = listOf(pumpFull, pumpNotFound)
    val pumpAddStep = listOf(pumpNotFound, pumpFull, btOff)
    val pumpScanStep = listOf(pumpNotFound, btOff, pumpFull, pumpTimeout)
    val pumpCodeStep = listOf(pumpMismatch, pumpTimeout)
    val pumpAll = listOf(pumpTimeout, pumpNotFound, pumpMismatch, pumpFull, btOff)
    val phoneAll = listOf(phoneNothing, phoneTimeout, phoneMismatch, phoneAgain, btOff)

    /** The topic that explains a pump pairing failure best. */
    fun forPump(f: PumpPairFailure): TroubleTopic = when (f) {
        PumpPairFailure.REJECTED -> pumpMismatch
        PumpPairFailure.TIMEOUT -> pumpTimeout
        PumpPairFailure.CONNECTION_FAILED -> pumpFull
        PumpPairFailure.BLUETOOTH_UNAVAILABLE -> btOff
        PumpPairFailure.OTHER -> pumpFull
        PumpPairFailure.SIMULATOR_ACTIVE -> pumpFull
    }

    fun failureTitleRes(f: PumpPairFailure): Int = when (f) {
        PumpPairFailure.REJECTED -> R.string.pump_fail_rejected_title
        PumpPairFailure.TIMEOUT -> R.string.pump_fail_timeout_title
        PumpPairFailure.CONNECTION_FAILED -> R.string.pump_fail_conn_title
        PumpPairFailure.BLUETOOTH_UNAVAILABLE -> R.string.pump_fail_bt_title
        PumpPairFailure.OTHER -> R.string.pump_fail_other_title
        PumpPairFailure.SIMULATOR_ACTIVE -> R.string.pump_fail_sim_title
    }

    fun failureBodyRes(f: PumpPairFailure): Int = when (f) {
        PumpPairFailure.REJECTED -> R.string.pump_fail_rejected_body
        PumpPairFailure.TIMEOUT -> R.string.pump_fail_timeout_body
        PumpPairFailure.CONNECTION_FAILED -> R.string.pump_fail_conn_body
        PumpPairFailure.BLUETOOTH_UNAVAILABLE -> R.string.pump_fail_bt_body
        PumpPairFailure.OTHER -> R.string.pump_fail_other_body
        PumpPairFailure.SIMULATOR_ACTIVE -> R.string.pump_fail_sim_body
    }
}
