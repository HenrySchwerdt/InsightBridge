/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trio.insightbridge.R
import app.trio.insightbridge.service.DiscoveredPump
import app.trio.insightbridge.service.PumpPairState
import app.trio.insightbridge.service.PumpPairingFlow
import app.trio.insightbridge.ui.components.BigButton
import app.trio.insightbridge.ui.components.BigCode
import app.trio.insightbridge.ui.components.ButtonStyle
import app.trio.insightbridge.ui.components.RuledCard
import app.trio.insightbridge.ui.components.TroubleshootSection
import app.trio.insightbridge.ui.components.WhatHappensNext
import app.trio.insightbridge.ui.pump.PumpButton
import app.trio.insightbridge.ui.pump.PumpInstructionCard
import app.trio.insightbridge.ui.pump.PumpStepContent
import app.trio.insightbridge.ui.theme.IbTheme
import app.trio.insightbridge.ui.util.CodeFormat

private const val STEP = 2
private const val TOTAL = 4

/** Pump sub-screens before the scan: 0 remove old device, 1 add device, 2 scan/code/result (flow driven). */
const val PUMP_SUB_REMOVE = 0
const val PUMP_SUB_ADD = 1
const val PUMP_SUB_SCAN = 2

private val removeContent = PumpStepContent(
    breadcrumbRes = R.string.pump_path_remove,
    youSeeRes = R.string.pump_see_remove,
    screenTitleRes = R.string.pump_scr_remove_title,
    screenLineRes = listOf(R.string.pump_scr_remove_l1, R.string.pump_scr_remove_l2),
    highlight = PumpButton.CONFIRM
)
private val addContent = PumpStepContent(
    breadcrumbRes = R.string.pump_path_add,
    youSeeRes = R.string.pump_see_add,
    screenTitleRes = R.string.pump_scr_add_title,
    screenLineRes = listOf(R.string.pump_scr_add_l1),
    highlight = PumpButton.CONFIRM
)
private val serialContent = PumpStepContent(
    breadcrumbRes = null,
    youSeeRes = R.string.pump_see_serial,
    screenTitleRes = R.string.pump_scr_add_title,
    screenLineRes = listOf(R.string.pump_scr_serial_l1, R.string.pump_scr_serial_l2, R.string.pump_scr_serial_l3),
    highlight = null
)
private val codeContent = PumpStepContent(
    breadcrumbRes = null,
    youSeeRes = R.string.pump_see_code,
    screenTitleRes = R.string.pump_scr_code_title,
    screenLineRes = listOf(R.string.pump_scr_code_l1, R.string.pump_scr_code_l2),
    highlight = PumpButton.CONFIRM
)
private val doneContent = PumpStepContent(
    breadcrumbRes = null,
    youSeeRes = R.string.pump_see_done,
    screenTitleRes = R.string.pump_scr_done_title,
    screenLineRes = emptyList(),
    highlight = null
)

/** Everything the pump pairing step needs from the wizard host. */
class PumpStepArgs(
    val flow: PumpPairingFlow,
    val sub: Int,
    val onSub: (Int) -> Unit,
    val onBack: (() -> Unit)?,
    val onFinished: () -> Unit,
    val simulated: Boolean,
    val pumpPaired: Boolean,
    val pairAgain: Boolean,
    val onPairAgain: () -> Unit,
    val onOpenDeveloper: () -> Unit
)

@Composable
fun PumpStep(a: PumpStepArgs) {
    when {
        a.simulated -> SimulatorBlocked(a)
        a.pumpPaired && !a.pairAgain && a.sub == PUMP_SUB_REMOVE -> AlreadyPaired(a)
        a.sub == PUMP_SUB_REMOVE -> RemoveScreen(a)
        a.sub == PUMP_SUB_ADD -> AddScreen(a)
        else -> ScanFlowScreen(a)
    }
}

@Composable
private fun SimulatorBlocked(a: PumpStepArgs) {
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_pump_sim_title), onBack = a.onBack,
        actions = {
            BigButton(stringResource(R.string.wz_pump_sim_continue), a.onFinished)
            BigButton(stringResource(R.string.wz_pump_sim_open_dev), a.onOpenDeveloper, style = ButtonStyle.Secondary)
        }
    ) {
        RuledCard(rule = IbTheme.colors.danger, container = IbTheme.colors.dangerContainer) {
            Text(stringResource(R.string.wz_pump_sim_body), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.onDangerContainer)
        }
        WhatHappensNext(stringResource(R.string.wz_pump_sim_next))
    }
}

@Composable
private fun AlreadyPaired(a: PumpStepArgs) {
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_pump_already_title), onBack = a.onBack,
        actions = {
            BigButton(stringResource(R.string.ui_next), a.onFinished)
            BigButton(stringResource(R.string.wz_pump_pair_again), a.onPairAgain, style = ButtonStyle.Secondary)
        }
    ) {
        RuledCard(rule = IbTheme.colors.ok) {
            Text(stringResource(R.string.wz_pump_already_body), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)
        }
        WhatHappensNext(stringResource(R.string.wz_pump_already_next))
    }
}

@Composable
private fun RemoveScreen(a: PumpStepArgs) {
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_pump_remove_title),
        subLabel = stringResource(R.string.wz_pump_sub, 1, 4), onBack = a.onBack,
        actions = { BigButton(stringResource(R.string.wz_pump_remove_done), { a.onSub(PUMP_SUB_ADD) }) },
        footer = { TroubleshootSection(Troubles.pumpRemoveStep, key = "ts-pump-remove") }
    ) {
        Text(stringResource(R.string.wz_pump_remove_body), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)
        PumpInstructionCard(removeContent)
        WhatHappensNext(stringResource(R.string.wz_pump_remove_next))
    }
}

@Composable
private fun AddScreen(a: PumpStepArgs) {
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_pump_add_title),
        subLabel = stringResource(R.string.wz_pump_sub, 2, 4), onBack = a.onBack,
        actions = { BigButton(stringResource(R.string.wz_pump_add_done), { a.flow.cancel(); a.onSub(PUMP_SUB_SCAN) }) },
        footer = { TroubleshootSection(Troubles.pumpAddStep, key = "ts-pump-add") }
    ) {
        Text(stringResource(R.string.wz_pump_add_body), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)
        PumpInstructionCard(addContent)
        WhatHappensNext(stringResource(R.string.wz_pump_add_next))
    }
}

@Composable
private fun ScanFlowScreen(a: PumpStepArgs) {
    val state by a.flow.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (a.flow.state.value is PumpPairState.Idle) a.flow.startScan() }
    // Leaving a running attempt through "Back" always cancels it.
    val back: () -> Unit = { a.flow.cancel(); a.onBack?.invoke() ?: a.onSub(PUMP_SUB_ADD) }
    when (val s = state) {
        PumpPairState.Idle -> BusyScreen(R.string.wz_pump_scan_title, R.string.wz_pump_busy_start, 3, back)
        is PumpPairState.Scanning -> ScanList(a, s.devices, back)
        is PumpPairState.Connecting -> BusyScreen(R.string.wz_pump_connecting_title, R.string.wz_pump_connecting_body, 3, back)
        is PumpPairState.ShowCode -> CodeCompare(a, s.code, back)
        PumpPairState.Finishing -> BusyScreen(R.string.wz_pump_finishing_title, R.string.wz_pump_finishing_body, 4, null)
        PumpPairState.Done -> PumpDone(a)
        is PumpPairState.Failed -> PumpFailed(a, s)
    }
}

@Composable
private fun BusyScreen(titleRes: Int, bodyRes: Int, part: Int, onBack: (() -> Unit)?) {
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(titleRes),
        subLabel = stringResource(R.string.wz_pump_sub, part, 4), onBack = onBack,
        actions = {},
        footer = { TroubleshootSection(Troubles.pumpScanStep, key = "ts-pump-busy") }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 5.dp)
            Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        WhatHappensNext(stringResource(R.string.wz_pump_busy_next))
    }
}

@Composable
private fun ScanList(a: PumpStepArgs, devices: List<DiscoveredPump>, onBack: () -> Unit) {
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_pump_scan_title),
        subLabel = stringResource(R.string.wz_pump_sub, 3, 4), onBack = onBack,
        actions = { BigButton(stringResource(R.string.wz_pump_scan_again), { a.flow.startScan() }, style = ButtonStyle.Secondary) },
        footer = { TroubleshootSection(Troubles.pumpScanStep, key = "ts-pump-scan") }
    ) {
        Text(stringResource(R.string.wz_pump_scan_body), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)
        PumpInstructionCard(serialContent)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(
                if (devices.isEmpty()) stringResource(R.string.wz_pump_scan_searching) else pluralStringResource(R.plurals.plural_devices_found, devices.size, devices.size),
                style = MaterialTheme.typography.titleMedium
            )
            if (devices.isEmpty()) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 4.dp)
            devices.forEach { d -> DeviceRow(d) { a.flow.select(d.address) } }
        }
        WhatHappensNext(stringResource(R.string.wz_pump_scan_next))
    }
}

@Composable
private fun DeviceRow(d: DiscoveredPump, onPick: () -> Unit) {
    val c = IbTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .border(1.dp, c.border, shape)
            .clickable(role = Role.Button, onClick = onPick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.titleLarge, color = c.foreground)
            Text(stringResource(R.string.wz_pump_scan_tap), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
        }
        Text("›", style = MaterialTheme.typography.headlineMedium, color = c.mutedForeground)
    }
}

@Composable
private fun CodeCompare(a: PumpStepArgs, code: String, onBack: () -> Unit) {
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_pump_code_title),
        subLabel = stringResource(R.string.wz_pump_sub, 4, 4), onBack = onBack,
        actions = {
            BigButton(stringResource(R.string.wz_code_same), { a.flow.confirmCode() }, style = ButtonStyle.Positive)
            BigButton(stringResource(R.string.wz_code_different), { a.flow.rejectCode() }, style = ButtonStyle.Secondary)
        },
        footer = { TroubleshootSection(Troubles.pumpCodeStep, key = "ts-pump-code") }
    ) {
        Text(stringResource(R.string.wz_pump_code_body), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)
        BigCode(
            display = code,
            spoken = CodeFormat.spoken(code),
            caption = stringResource(R.string.wz_pump_code_caption),
            style = MaterialTheme.typography.displayMedium
        )
        PumpInstructionCard(codeContent)
        WhatHappensNext(stringResource(R.string.wz_pump_code_next))
    }
}

@Composable
private fun PumpDone(a: PumpStepArgs) {
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_pump_done_title), onBack = null,
        actions = { BigButton(stringResource(R.string.ui_next), { a.flow.cancel(); a.onFinished() }) }
    ) {
        RuledCard(rule = IbTheme.colors.ok, container = IbTheme.colors.okContainer) {
            Text(stringResource(R.string.wz_pump_done_body), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.onOkContainer)
        }
        PumpInstructionCard(doneContent)
        WhatHappensNext(stringResource(R.string.wz_pump_done_next))
    }
}

@Composable
private fun PumpFailed(a: PumpStepArgs, s: PumpPairState.Failed) {
    val topic = Troubles.forPump(s.reason)
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(Troubles.failureTitleRes(s.reason)),
        onBack = { a.flow.cancel(); a.onBack?.invoke() ?: a.onSub(PUMP_SUB_ADD) },
        actions = { BigButton(stringResource(R.string.ui_try_again), { a.flow.cancel(); a.onSub(PUMP_SUB_ADD) }) },
        footer = {
            TroubleshootSection(
                Troubles.pumpAll.let { all -> if (topic in all) all else all + topic },
                key = "ts-pump-fail-${s.reason}", highlight = topic
            )
        }
    ) {
        RuledCard(rule = IbTheme.colors.danger, container = IbTheme.colors.dangerContainer) {
            Text(stringResource(Troubles.failureBodyRes(s.reason)), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.onDangerContainer)
        }
        s.detail?.let { detail ->
            Text(stringResource(R.string.wz_pump_fail_detail_label), style = MaterialTheme.typography.labelLarge, color = IbTheme.colors.mutedForeground)
            Text(detail, style = app.trio.insightbridge.ui.theme.IbMono, color = IbTheme.colors.foreground)
        }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        BigButton(
            stringResource(R.string.ui_share_log),
            { app.trio.insightbridge.util.LogShare.share(ctx, a.flow.logLines()) },
            style = app.trio.insightbridge.ui.components.ButtonStyle.Secondary
        )
        WhatHappensNext(stringResource(R.string.wz_pump_fail_next))
    }
}
