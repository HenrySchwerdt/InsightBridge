/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.alerts

import app.trio.insightbridge.R

/** Alert categories as sent by the core (AlertCatalog.categoryOf). */
enum class AlertCategory { REMINDER, WARNING, MAINTENANCE, ERROR;
    companion object {
        fun of(wire: String?): AlertCategory = when (wire) {
            "reminder" -> REMINDER; "warning" -> WARNING; "maintenance" -> MAINTENANCE; else -> ERROR
        }
    }
}

/**
 * Plain-language description of a pump alert, in resources (English and German).
 * [titleRes] short title, [meaningRes] what it means, [actionRes] what to do ON THE PUMP,
 * [appActionRes] what the user can do here in the app, [codeRes] the code printed on the pump (e.g. W31).
 */
data class AlertInfo(
    val titleRes: Int,
    val meaningRes: Int,
    val actionRes: Int,
    val codeRes: Int = titleRes,
    val appActionRes: Int = R.string.alert_app_maintenance,
    val category: AlertCategory = AlertCategory.MAINTENANCE,
    val known: Boolean = true
) {

    companion object {

        private val unknown = AlertInfo(
            R.string.alert_unknown_title, R.string.alert_unknown_meaning, R.string.alert_unknown_pump,
            R.string.alert_unknown_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE, known = false
        )

        private val table: Map<String, AlertInfo> = mapOf(
            "REMINDER_01" to AlertInfo(R.string.alert_r1_title, R.string.alert_r1_meaning, R.string.alert_r1_pump, R.string.alert_r1_code, R.string.alert_app_reminder, AlertCategory.REMINDER),
            "REMINDER_02" to AlertInfo(R.string.alert_r2_title, R.string.alert_r2_meaning, R.string.alert_r2_pump, R.string.alert_r2_code, R.string.alert_app_reminder, AlertCategory.REMINDER),
            "REMINDER_03" to AlertInfo(R.string.alert_r3_title, R.string.alert_r3_meaning, R.string.alert_r3_pump, R.string.alert_r3_code, R.string.alert_app_reminder, AlertCategory.REMINDER),
            "REMINDER_04" to AlertInfo(R.string.alert_r4_title, R.string.alert_r4_meaning, R.string.alert_r4_pump, R.string.alert_r4_code, R.string.alert_app_reminder, AlertCategory.REMINDER),
            "REMINDER_07" to AlertInfo(R.string.alert_r7_title, R.string.alert_r7_meaning, R.string.alert_r7_pump, R.string.alert_r7_code, R.string.alert_app_reminder, AlertCategory.REMINDER),
            "WARNING_31" to AlertInfo(R.string.alert_w31_title, R.string.alert_w31_meaning, R.string.alert_w31_pump, R.string.alert_w31_code, R.string.alert_app_warning, AlertCategory.WARNING),
            "WARNING_32" to AlertInfo(R.string.alert_w32_title, R.string.alert_w32_meaning, R.string.alert_w32_pump, R.string.alert_w32_code, R.string.alert_app_warning, AlertCategory.WARNING),
            "WARNING_33" to AlertInfo(R.string.alert_w33_title, R.string.alert_w33_meaning, R.string.alert_w33_pump, R.string.alert_w33_code, R.string.alert_app_warning, AlertCategory.WARNING),
            "WARNING_34" to AlertInfo(R.string.alert_w34_title, R.string.alert_w34_meaning, R.string.alert_w34_pump, R.string.alert_w34_code, R.string.alert_app_warning, AlertCategory.WARNING),
            "WARNING_36" to AlertInfo(R.string.alert_w36_title, R.string.alert_w36_meaning, R.string.alert_w36_pump, R.string.alert_w36_code, R.string.alert_app_warning, AlertCategory.WARNING),
            "WARNING_38" to AlertInfo(R.string.alert_w38_title, R.string.alert_w38_meaning, R.string.alert_w38_pump, R.string.alert_w38_code, R.string.alert_app_warning, AlertCategory.WARNING),
            "WARNING_39" to AlertInfo(R.string.alert_w39_title, R.string.alert_w39_meaning, R.string.alert_w39_pump, R.string.alert_w39_code, R.string.alert_app_warning, AlertCategory.WARNING),
            "MAINTENANCE_20" to AlertInfo(R.string.alert_m20_title, R.string.alert_m20_meaning, R.string.alert_m20_pump, R.string.alert_m20_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE),
            "MAINTENANCE_21" to AlertInfo(R.string.alert_m21_title, R.string.alert_m21_meaning, R.string.alert_m21_pump, R.string.alert_m21_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE),
            "MAINTENANCE_22" to AlertInfo(R.string.alert_m22_title, R.string.alert_m22_meaning, R.string.alert_m22_pump, R.string.alert_m22_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE),
            "MAINTENANCE_23" to AlertInfo(R.string.alert_m23_title, R.string.alert_m23_meaning, R.string.alert_m23_pump, R.string.alert_m23_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE),
            "MAINTENANCE_24" to AlertInfo(R.string.alert_m24_title, R.string.alert_m24_meaning, R.string.alert_m24_pump, R.string.alert_m24_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE),
            "MAINTENANCE_25" to AlertInfo(R.string.alert_m25_title, R.string.alert_m25_meaning, R.string.alert_m25_pump, R.string.alert_m25_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE),
            "MAINTENANCE_26" to AlertInfo(R.string.alert_m26_title, R.string.alert_m26_meaning, R.string.alert_m26_pump, R.string.alert_m26_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE),
            "MAINTENANCE_27" to AlertInfo(R.string.alert_m27_title, R.string.alert_m27_meaning, R.string.alert_m27_pump, R.string.alert_m27_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE),
            "MAINTENANCE_28" to AlertInfo(R.string.alert_m28_title, R.string.alert_m28_meaning, R.string.alert_m28_pump, R.string.alert_m28_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE),
            "MAINTENANCE_29" to AlertInfo(R.string.alert_m29_title, R.string.alert_m29_meaning, R.string.alert_m29_pump, R.string.alert_m29_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE),
            "MAINTENANCE_30" to AlertInfo(R.string.alert_m30_title, R.string.alert_m30_meaning, R.string.alert_m30_pump, R.string.alert_m30_code, R.string.alert_app_maintenance, AlertCategory.MAINTENANCE),
            "ERROR_6" to AlertInfo(R.string.alert_e6_title, R.string.alert_e6_meaning, R.string.alert_e6_pump, R.string.alert_e6_code, R.string.alert_app_error, AlertCategory.ERROR),
            "ERROR_10" to AlertInfo(R.string.alert_e10_title, R.string.alert_e10_meaning, R.string.alert_e10_pump, R.string.alert_e10_code, R.string.alert_app_error, AlertCategory.ERROR),
            "ERROR_13" to AlertInfo(R.string.alert_e13_title, R.string.alert_e13_meaning, R.string.alert_e13_pump, R.string.alert_e13_code, R.string.alert_app_error, AlertCategory.ERROR)
        )

        /** Info for an AlertType enum NAME (e.g. WARNING_31); unknown names get a generic fallback. */
        fun of(alertType: String): AlertInfo = table[alertType] ?: unknown

        val knownTypes: Set<String> get() = table.keys
    }
}
