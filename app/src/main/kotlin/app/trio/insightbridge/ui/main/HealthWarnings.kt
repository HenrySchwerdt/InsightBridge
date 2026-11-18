/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.main

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.trio.insightbridge.BridgeRuntime
import app.trio.insightbridge.R
import app.trio.insightbridge.service.BridgeService
import app.trio.insightbridge.service.HealthSnapshot
import app.trio.insightbridge.service.Perms
import app.trio.insightbridge.ui.components.WarningBanner
import app.trio.insightbridge.ui.wizard.VendorSettings
import app.trio.insightbridge.ui.wizard.requestBatteryExemption
import androidx.compose.ui.unit.dp

/** Stackable red banners, one per [HealthSnapshot.Warning], each with a fix action. */
@Composable
fun HealthWarnings(rt: BridgeRuntime, warnings: List<HealthSnapshot.Warning>, modifier: Modifier = Modifier) {
    if (warnings.isEmpty()) return
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (Perms.bluetoothGranted(ctx)) BridgeService.start(ctx)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        warnings.forEach { w ->
            val (msg, label) = texts(w)
            WarningBanner(
                message = stringResource(msg),
                actionLabel = stringResource(label),
                onAction = { fix(ctx, rt, w) { permLauncher.launch(Perms.allRuntimePermissions().toTypedArray()) } }
            )
        }
    }
}

private fun texts(w: HealthSnapshot.Warning): Pair<Int, Int> = when (w) {
    HealthSnapshot.Warning.BATTERY_OPTIMIZATION -> R.string.warn_battery to R.string.warn_battery_fix
    HealthSnapshot.Warning.NOTIFICATIONS_OFF -> R.string.warn_notifications to R.string.warn_notifications_fix
    HealthSnapshot.Warning.BLUETOOTH_OFF -> R.string.warn_bluetooth to R.string.warn_bluetooth_fix
    HealthSnapshot.Warning.PERMISSIONS_MISSING -> R.string.warn_permissions to R.string.warn_permissions_fix
    HealthSnapshot.Warning.SIMULATOR_CONFLICT -> R.string.warn_sim_conflict to R.string.warn_sim_conflict_fix
    HealthSnapshot.Warning.NOT_ADVERTISING -> R.string.warn_not_advertising to R.string.warn_not_advertising_fix
}

private fun fix(ctx: Context, rt: BridgeRuntime, w: HealthSnapshot.Warning, askPermissions: () -> Unit) {
    when (w) {
        HealthSnapshot.Warning.BATTERY_OPTIMIZATION -> requestBatteryExemption(ctx)
        HealthSnapshot.Warning.NOTIFICATIONS_OFF -> SystemIntents.notificationSettings(ctx)
        HealthSnapshot.Warning.BLUETOOTH_OFF -> SystemIntents.bluetoothSettings(ctx)
        HealthSnapshot.Warning.PERMISSIONS_MISSING -> askPermissions()
        HealthSnapshot.Warning.SIMULATOR_CONFLICT -> rt.disableSimulator()
        HealthSnapshot.Warning.NOT_ADVERTISING -> BridgeService.start(ctx)
    }
}

internal fun openAppDetails(ctx: Context) = VendorSettings.openAppDetails(ctx)
