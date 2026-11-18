/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.protocol.AlertDto
import app.trio.insightbridge.ui.components.BadgeVariant
import app.trio.insightbridge.ui.components.BigButton
import app.trio.insightbridge.ui.components.ButtonStyle
import app.trio.insightbridge.ui.components.IbBadge
import app.trio.insightbridge.ui.components.IbCard
import app.trio.insightbridge.ui.components.Separator
import app.trio.insightbridge.ui.theme.IbTheme
import java.util.Locale

/**
 * Active pump alert. Category is shown by badge text ("Warning W31"), icon and dot, never by color alone.
 * Confirm / Snooze are explicit user taps only; the bridge never confirms by itself.
 */
@Composable
fun AlertCard(
    alert: AlertDto,
    busy: Boolean,
    feedback: String?,
    onConfirm: () -> Unit,
    onSnooze: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = IbTheme.colors
    val info = AlertInfo.of(alert.alertType)
    val category = if (info.known) info.category else AlertCategory.of(alert.category)
    val snoozed = alert.status == "snoozed"
    val isError = category == AlertCategory.ERROR
    val dot = when (category) {
        AlertCategory.ERROR -> c.dotDanger
        AlertCategory.REMINDER -> c.mutedForeground
        else -> c.dotWarn
    }
    IbCard(
        modifier,
        borderColor = if (isError) c.destructive else c.border,
        background = if (isError) c.dangerSurface else c.background
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (category == AlertCategory.REMINDER) Icons.Filled.Info else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (isError) c.destructive else c.foreground,
                modifier = Modifier.size(20.dp)
            )
            Text(
                stringResource(R.string.ui_alert_active_label),
                style = MaterialTheme.typography.titleSmall,
                color = if (isError) c.onDangerSurface else c.foreground,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            IbBadge(if (info.known) stringResource(info.codeRes) else alert.alertType, variant = if (isError) BadgeVariant.Destructive else BadgeVariant.Outline, dot = dot)
        }
        val fg = if (isError) c.onDangerSurface else c.foreground
        val muted = if (isError) c.onDangerSurface else c.mutedForeground
        Text(stringResource(info.titleRes), style = MaterialTheme.typography.headlineMedium, color = fg)
        if (snoozed) IbBadge(stringResource(R.string.ui_alert_snoozed_label), variant = BadgeVariant.Secondary)
        alert.remainingInsulin?.let {
            Text(stringResource(R.string.ui_alert_remaining, String.format(Locale.getDefault(), "%.1f", it)), style = MaterialTheme.typography.titleSmall, color = fg)
        }
        alert.tbrPercent?.let { Text(stringResource(R.string.ui_alert_tbr, it), style = MaterialTheme.typography.titleSmall, color = fg) }
        Separator()
        Block(stringResource(R.string.ui_alert_meaning_head), stringResource(info.meaningRes), fg, muted)
        Block(stringResource(R.string.ui_alert_pump_head), stringResource(info.actionRes), fg, muted)
        Block(stringResource(R.string.ui_alert_app_head), stringResource(info.appActionRes), fg, muted)
        Text(stringResource(R.string.ui_alert_never_auto), style = MaterialTheme.typography.labelSmall, color = muted)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(stringResource(R.string.action_confirm_alert), onConfirm, enabled = !busy, style = ButtonStyle.Primary)
            BigButton(stringResource(R.string.action_snooze_alert), onSnooze, enabled = !busy && !snoozed, style = ButtonStyle.Secondary)
            if (snoozed) Text(stringResource(R.string.ui_alert_snooze_disabled), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        if (feedback != null) {
            Text(
                feedback,
                style = MaterialTheme.typography.titleSmall,
                color = fg,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

@Composable
private fun Block(head: String, body: String, headColor: androidx.compose.ui.graphics.Color, bodyColor: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(head, style = MaterialTheme.typography.titleSmall, color = headColor, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyMedium, color = bodyColor)
    }
}
