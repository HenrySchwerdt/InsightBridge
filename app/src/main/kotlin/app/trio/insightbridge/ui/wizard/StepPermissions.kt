/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.wizard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.trio.insightbridge.R
import app.trio.insightbridge.service.BridgeService
import app.trio.insightbridge.service.Perms
import app.trio.insightbridge.ui.components.BigButton
import app.trio.insightbridge.ui.components.ButtonStyle
import app.trio.insightbridge.ui.components.RuledCard
import app.trio.insightbridge.ui.components.IbCard
import app.trio.insightbridge.ui.components.PillButton
import app.trio.insightbridge.ui.components.Separator
import app.trio.insightbridge.ui.components.TroubleshootSection
import app.trio.insightbridge.ui.components.WhatHappensNext
import app.trio.insightbridge.ui.theme.IbTheme
import app.trio.insightbridge.ui.util.Vendor

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Re-reads platform state every time the user comes back from a system screen. */
@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

private const val TOTAL = 4

/** Step 1, part 1: Bluetooth + notification permissions. Starts the service once Bluetooth is granted. */
@Composable
fun PermissionsScreen(onNext: () -> Unit, onBack: (() -> Unit)?) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val resume = rememberResumeTick()
    val btOk = remember(tick, resume) { Perms.bluetoothGranted(ctx) }
    val notifOk = remember(tick, resume) { Perms.notificationsPermitted(ctx) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        tick++
        if (Perms.bluetoothGranted(ctx)) BridgeService.start(ctx)
    }
    androidx.compose.runtime.LaunchedEffect(btOk) { if (btOk) BridgeService.start(ctx) }

    val activity = ctx.findActivity()
    val permanentlyDenied = asked && activity != null && Perms.allRuntimePermissions()
        .filter { androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        .let { missing -> missing.isNotEmpty() && missing.none { activity.shouldShowRequestPermissionRationale(it) } }

    val onAllow: () -> Unit = { if (permanentlyDenied) VendorSettings.openAppDetails(ctx) else launchAsk(launcher) }
    WizardFrame(
        step = 1, totalSteps = TOTAL,
        title = stringResource(R.string.wz_perm_title),
        onBack = onBack,
        showVersion = true,
        actions = {
            when {
                btOk && notifOk -> BigButton(stringResource(R.string.ui_next), onNext)
                btOk -> {
                    BigButton(stringResource(R.string.ui_next), onNext)
                    BigButton(stringResource(R.string.wz_perm_ask_again), { launchAsk(launcher) }, style = ButtonStyle.Text)
                }
                permanentlyDenied -> BigButton(stringResource(R.string.wz_perm_open_settings), { VendorSettings.openAppDetails(ctx) })
                else -> BigButton(stringResource(R.string.wz_perm_allow), { launchAsk(launcher) })
            }
        },
        footer = { TroubleshootSection(Troubles.permissionsStep, key = "ts-perm", highlight = if (asked && !btOk) Troubles.permDenied else null) }
    ) {
        Text(stringResource(R.string.wz_perm_intro), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)
        IbCard(padding = false) {
            PermRow(stringResource(R.string.wz_perm_bt_name), btOk, onAllow)
            Separator()
            PermRow(stringResource(R.string.wz_perm_notif_name), notifOk, onAllow)
        }
        if (!Perms.bluetoothOn(ctx)) Text(stringResource(R.string.wz_perm_bt_is_off), style = MaterialTheme.typography.bodySmall, color = IbTheme.colors.danger)
        if (btOk && !notifOk) Text(stringResource(R.string.wz_perm_notif_missing), style = MaterialTheme.typography.bodySmall, color = IbTheme.colors.attention)
        if (asked && !btOk) Text(stringResource(R.string.wz_perm_bt_required), style = MaterialTheme.typography.bodySmall, color = IbTheme.colors.danger)
        WhatHappensNext(stringResource(R.string.wz_perm_next))
    }
}

private fun launchAsk(launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
    launcher.launch(Perms.allRuntimePermissions().toTypedArray())
}

@Composable
private fun PermRow(name: String, granted: Boolean, onAllow: () -> Unit) {
    val c = IbTheme.colors
    val state = stringResource(if (granted) R.string.wz_perm_state_ok else R.string.wz_perm_state_missing)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(if (granted) c.foreground else Color.Transparent, CircleShape)
                .border(1.5.dp, if (granted) c.foreground else c.border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (granted) Icon(Icons.Filled.Check, contentDescription = null, tint = c.background, modifier = Modifier.size(16.dp))
        }
        Text(name, style = MaterialTheme.typography.titleMedium, color = c.foreground, modifier = Modifier.weight(1f))
        if (granted) {
            Text(state, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
        } else {
            PillButton(stringResource(R.string.ui_allow), onAllow)
        }
    }
}

/** Step 1, part 2: exemption from battery optimization. */
@Composable
fun BatteryScreen(onNext: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val resume = rememberResumeTick()
    val exempt = remember(resume) { Perms.batteryExempt(ctx) }
    var failedToOpen by rememberSaveable { mutableStateOf(false) }
    WizardFrame(
        step = 1, totalSteps = TOTAL,
        title = stringResource(R.string.wz_batt_title),
        subLabel = stringResource(R.string.wz_perm_sub, 2, 3),
        onBack = onBack,
        actions = {
            if (exempt) {
                BigButton(stringResource(R.string.ui_next), onNext)
            } else {
                BigButton(stringResource(R.string.wz_batt_allow), { failedToOpen = !requestBatteryExemption(ctx) })
                BigButton(stringResource(R.string.wz_batt_continue_anyway), onNext, style = ButtonStyle.Text)
            }
        },
        footer = { TroubleshootSection(Troubles.batteryStep, key = "ts-batt", highlight = if (failedToOpen) Troubles.batteryScreen else null) }
    ) {
        Text(stringResource(R.string.wz_batt_intro), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)
        IbCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(10.dp).background(if (exempt) IbTheme.colors.dotOk else IbTheme.colors.dotWarn, CircleShape))
                Text(
                    stringResource(if (exempt) R.string.wz_batt_state_ok else R.string.wz_batt_state_missing),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        if (!exempt) Text(stringResource(R.string.wz_batt_warning), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.danger)
        WhatHappensNext(stringResource(R.string.wz_batt_next))
    }
}

internal fun requestBatteryExemption(ctx: Context): Boolean {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    return try {
        ctx.startActivity(direct); true
    } catch (_: Throwable) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); true
        } catch (_: Throwable) {
            VendorSettings.openAppDetails(ctx)
        }
    }
}

/** Step 1, part 3: vendor "autostart / background" settings. */
@Composable
fun VendorScreen(onNext: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vendor = remember { VendorSettings.detect() }
    var visited by rememberSaveable { mutableStateOf(false) }
    var openedKind by rememberSaveable { mutableStateOf(-1) }
    WizardFrame(
        step = 1, totalSteps = TOTAL,
        title = stringResource(R.string.wz_vendor_title),
        subLabel = stringResource(R.string.wz_perm_sub, 3, 3),
        onBack = onBack,
        actions = {
            if (!visited) {
                BigButton(stringResource(R.string.wz_vendor_open), {
                    openedKind = VendorSettings.open(ctx, vendor).ordinal
                    visited = true
                })
                BigButton(stringResource(R.string.wz_vendor_done_already), onNext, style = ButtonStyle.Text)
            } else {
                BigButton(stringResource(R.string.ui_next), onNext)
                BigButton(stringResource(R.string.wz_vendor_open_again), { openedKind = VendorSettings.open(ctx, vendor).ordinal }, style = ButtonStyle.Secondary)
            }
        },
        footer = { TroubleshootSection(Troubles.batteryStep.reversed(), key = "ts-vendor") }
    ) {
        Text(stringResource(R.string.wz_vendor_intro), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)
        IbCard {
            Text(stringResource(vendorTitle(vendor)), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(vendorSteps(vendor)), style = MaterialTheme.typography.bodyMedium, color = IbTheme.colors.mutedForeground)
        }
        if (openedKind == VendorSettings.Opened.APP_DETAILS.ordinal) {
            Text(stringResource(R.string.wz_vendor_opened_generic), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
        }
        Text(stringResource(R.string.wz_vendor_dontkill), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WhatHappensNext(stringResource(R.string.wz_vendor_next))
    }
}

private fun vendorTitle(v: Vendor): Int = when (v) {
    Vendor.SAMSUNG -> R.string.vendor_samsung_t
    Vendor.XIAOMI -> R.string.vendor_xiaomi_t
    Vendor.HUAWEI -> R.string.vendor_huawei_t
    Vendor.OPPO -> R.string.vendor_oppo_t
    Vendor.VIVO -> R.string.vendor_vivo_t
    Vendor.ASUS -> R.string.vendor_asus_t
    Vendor.OTHER -> R.string.vendor_other_t
}

private fun vendorSteps(v: Vendor): Int = when (v) {
    Vendor.SAMSUNG -> R.string.vendor_samsung_b
    Vendor.XIAOMI -> R.string.vendor_xiaomi_b
    Vendor.HUAWEI -> R.string.vendor_huawei_b
    Vendor.OPPO -> R.string.vendor_oppo_b
    Vendor.VIVO -> R.string.vendor_vivo_b
    Vendor.ASUS -> R.string.vendor_asus_b
    Vendor.OTHER -> R.string.vendor_other_b
}
