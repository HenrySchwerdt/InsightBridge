/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.trio.insightbridge.ui.components.IbSwitch
import app.trio.insightbridge.ui.theme.IbTheme
import app.trio.insightbridge.ui.components.Separator
import app.trio.insightbridge.ui.components.IbCard
import app.trio.insightbridge.ui.components.CardHeader
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.BridgeRuntime
import app.trio.insightbridge.R
import app.trio.insightbridge.service.BridgeService
import kotlinx.coroutines.launch
import app.trio.insightbridge.ui.components.BigButton
import app.trio.insightbridge.ui.components.ButtonStyle
import app.trio.insightbridge.ui.components.IbChoiceDialog
import app.trio.insightbridge.ui.components.IbConfirmDialog
import app.trio.insightbridge.ui.components.RuledCard

private const val D_NONE = 0
private const val D_REPAIR_PUMP = 1
private const val D_CHOOSE_UNPAIR = 2
private const val D_UNPAIR_PUMP = 3
private const val D_UNPAIR_PHONE = 4

/** Re-pair / unpair buttons with confirmation dialogs, and the "keep pump connected" switch. */
@Composable
fun MainActions(
    rt: BridgeRuntime,
    onRepairPump: () -> Unit,
    onRepairPhone: () -> Unit,
    logOpen: Boolean,
    onToggleLog: () -> Unit,
    modifier: Modifier = Modifier,
    onNote: (Int) -> Unit = {}
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var dialog by rememberSaveable { mutableIntStateOf(D_NONE) }
    var alwaysOn by remember { mutableStateOf(rt.settings.alwaysOnLink) }
    val dismiss = { dialog = D_NONE }
    val c = IbTheme.colors

    IbCard(modifier, padding = false) {
        CardHeader(stringResource(R.string.ui_settings_title), Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp))
        SettingsRow(
            title = stringResource(R.string.ui_link_mode_switch),
            description = stringResource(R.string.ui_link_mode_explain),
            trailing = {
                IbSwitch(alwaysOn)
            },
            modifier = Modifier.toggleable(value = alwaysOn, role = Role.Switch, onValueChange = {
                alwaysOn = it
                rt.setAlwaysOn(it)
                onNote(if (it) R.string.note_link_always else R.string.note_link_on_demand)
            })
        )
        Separator()
        SettingsRow(stringResource(R.string.ui_repair_pump), onClick = { dialog = D_REPAIR_PUMP })
        Separator()
        SettingsRow(stringResource(R.string.ui_repair_phone), onClick = onRepairPhone)
        Separator()
        SettingsRow(
            title = stringResource(R.string.ui_restart_ble),
            description = stringResource(R.string.ui_restart_ble_explain),
            onClick = {
                // Rebuilds GATT server + advertising. If the service itself is gone, start it (it comes up advertising).
                if (!rt.restartBle("user pressed restart")) BridgeService.start(ctx)
                onNote(R.string.note_ble_restarted)
            }
        )
        Separator()
        SettingsRow(stringResource(R.string.ui_log_title), onClick = onToggleLog, trailingText = if (logOpen) "−" else "+")
        Separator()
        SettingsRow(stringResource(R.string.ui_unpair), onClick = { dialog = D_CHOOSE_UNPAIR }, danger = true)
    }

    when (dialog) {
        D_REPAIR_PUMP -> IbConfirmDialog(
            title = stringResource(R.string.dlg_repair_pump_title),
            text = stringResource(R.string.dlg_repair_pump_body),
            confirmLabel = stringResource(R.string.ui_repair_pump_confirm),
            onConfirm = { dialog = D_NONE; onRepairPump() },
            onDismiss = dismiss
        )
        D_CHOOSE_UNPAIR -> IbChoiceDialog(
            title = stringResource(R.string.dlg_unpair_choose_title),
            text = stringResource(R.string.dlg_unpair_choose_body),
            options = listOf(
                stringResource(R.string.ui_unpair_pump) to { dialog = D_UNPAIR_PUMP },
                stringResource(R.string.ui_unpair_phone) to { dialog = D_UNPAIR_PHONE }
            ),
            onDismiss = dismiss
        )
        D_UNPAIR_PUMP -> IbConfirmDialog(
            title = stringResource(R.string.dlg_unpair_pump_title),
            text = stringResource(R.string.dlg_unpair_pump_body),
            confirmLabel = stringResource(R.string.ui_unpair_pump_confirm),
            destructive = true,
            onConfirm = {
                dialog = D_NONE
                // Off the UI thread: resetting the pump connection takes its monitor.
                rt.scope.launch(kotlinx.coroutines.Dispatchers.Default) { rt.unpairPump() }
                onNote(R.string.note_pump_unpaired)
            },
            onDismiss = dismiss
        )
        D_UNPAIR_PHONE -> IbConfirmDialog(
            title = stringResource(R.string.dlg_unpair_phone_title),
            text = stringResource(R.string.dlg_unpair_phone_body),
            confirmLabel = stringResource(R.string.ui_unpair_phone_confirm),
            destructive = true,
            onConfirm = {
                dialog = D_NONE
                rt.scope.launch(kotlinx.coroutines.Dispatchers.Default) { rt.unpairPhone() }
                onNote(R.string.note_phone_unpaired)
            },
            onDismiss = dismiss
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    trailingText: String = "›",
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val c = IbTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (danger) c.danger else c.foreground)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
        }
        if (trailing != null) trailing() else Text(trailingText, style = MaterialTheme.typography.titleLarge, color = c.mutedForeground)
    }
}
