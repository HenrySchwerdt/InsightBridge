/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.trio.insightbridge.ui.components.BadgeVariant
import app.trio.insightbridge.ui.components.IbBadge
import app.trio.insightbridge.ui.components.IbAlert
import app.trio.insightbridge.ui.components.Separator
import app.trio.insightbridge.ui.components.IbCard
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.protocol.StatusDto
import app.trio.insightbridge.service.HealthSnapshot
import app.trio.insightbridge.ui.components.BigButton
import app.trio.insightbridge.ui.components.KeyValueRow
import app.trio.insightbridge.ui.components.RuledCard
import app.trio.insightbridge.ui.components.WhatHappensNext
import app.trio.insightbridge.ui.theme.IbTheme
import app.trio.insightbridge.ui.util.phoneLinkText
import app.trio.insightbridge.ui.util.pumpLinkText

private const val STEP = 4
private const val TOTAL = 4

/** Step 4: summary and "open the main screen" (the host marks setup done and starts service + watchdog). */
@Composable
fun DoneStep(health: HealthSnapshot, status: StatusDto?, onBack: () -> Unit, onOpenMain: () -> Unit) {
    val allGood = health.pumpPaired || health.simulated
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_done_title), onBack = onBack,
        actions = { BigButton(stringResource(R.string.wz_done_open_main), onOpenMain) }
    ) {
        Text(stringResource(R.string.wz_done_intro), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)
        IbCard(padding = false) {
            DoneRow(stringResource(R.string.ui_pump_link), pumpLinkText(health.pumpLink), if (allGood) IbTheme.colors.dotOk else IbTheme.colors.dotWarn)
            Separator()
            DoneRow(stringResource(R.string.ui_phone_link), phoneLinkText(health.phonePaired, health.phoneLink), if (health.phonePaired) IbTheme.colors.dotOk else IbTheme.colors.dotWarn)
            status?.battery?.percent?.let { Separator(); DoneRow(stringResource(R.string.ui_battery), stringResource(R.string.ui_percent, it), null) }
            status?.reservoir?.units?.let { Separator(); DoneRow(stringResource(R.string.ui_reservoir), stringResource(R.string.ui_units_fmt, String.format(java.util.Locale.getDefault(), "%.1f", it)), null) }
        }
        if (!health.phonePaired) IbAlert(description = stringResource(R.string.wz_done_phone_missing))
        if (!allGood) IbAlert(description = stringResource(R.string.wz_done_pump_missing))
        Text(stringResource(R.string.wz_done_trio), style = MaterialTheme.typography.bodySmall, color = IbTheme.colors.mutedForeground)
        WhatHappensNext(stringResource(R.string.wz_done_next))
    }
}

@Composable
private fun DoneRow(label: String, value: String, dot: androidx.compose.ui.graphics.Color?) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IbBadge(value.replaceFirstChar { it.uppercase() }, variant = BadgeVariant.Outline, dot = dot)
    }
}
