/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.protocol.StatusDto
import app.trio.insightbridge.ui.components.BadgeVariant
import app.trio.insightbridge.ui.components.IbBadge
import app.trio.insightbridge.ui.components.IbCard
import app.trio.insightbridge.ui.theme.IbTheme
import app.trio.insightbridge.ui.theme.TabularNumbers
import app.trio.insightbridge.ui.util.StatusAge
import app.trio.insightbridge.ui.util.operatingModeIsStopped
import app.trio.insightbridge.ui.util.operatingModeText
import app.trio.insightbridge.util.Format
import java.util.Locale

private fun units(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)
private fun rate(v: Double): String = String.format(Locale.getDefault(), "%.2f", v)

/** Last known pump status as four compact stat cards. Missing values read "unknown"; data older than 15 minutes is marked. */
@Composable
fun StatusCard(status: StatusDto?, now: Long, modifier: Modifier = Modifier) {
    val unknown = stringResource(R.string.ui_unknown)
    val none = stringResource(R.string.ui_none_active)
    val at = StatusAge.parse(status?.timestamp)
    val stale = status == null || StatusAge.isStale(now, at)
    val c = IbTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ui_status_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            if (stale) IbBadge(stringResource(R.string.ui_stale), variant = BadgeVariant.Outline, dot = c.dotWarn)
        }
        if (status == null) {
            Text(stringResource(R.string.ui_status_none), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
        } else {
            Text(
                if (at == null) stringResource(R.string.ui_status_age_unknown) else stringResource(R.string.ui_status_age, Format.duration(now - at)),
                style = MaterialTheme.typography.bodySmall,
                color = if (stale) c.attention else c.mutedForeground
            )
            if (stale && at != null) Text(stringResource(R.string.ui_stale_explain), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
        }
        val reservoir = status?.reservoir?.units
        val battery = status?.battery?.percent
        val basal = status?.basal?.rate
        val tbr = status?.tbr
        val boluses = status?.boluses
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(stringResource(R.string.ui_reservoir), reservoir?.let { units(it) }, stringResource(R.string.ui_unit_u), null, unknown, Modifier.weight(1f).fillMaxHeight())
            StatCard(stringResource(R.string.ui_battery), battery?.toString(), stringResource(R.string.ui_unit_percent), null, unknown, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                stringResource(R.string.ui_basal), basal?.let { rate(it) }, stringResource(R.string.ui_unit_uh),
                tbr?.let { stringResource(R.string.ui_tbr_fmt, it.percent, it.remaining) }, unknown, Modifier.weight(1f).fillMaxHeight()
            )
            val first = boluses?.firstOrNull()
            StatCard(
                stringResource(R.string.ui_bolus),
                when {
                    boluses == null -> null
                    first == null -> "–"
                    else -> units(first.amount)
                },
                if (first != null) stringResource(R.string.ui_unit_u) else null,
                when {
                    first != null -> stringResource(R.string.ui_bolus_remaining, units(first.remaining))
                    boluses != null -> none
                    else -> null
                },
                unknown, Modifier.weight(1f).fillMaxHeight()
            )
        }
        if (status != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_operating_mode), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                IbBadge(
                    operatingModeText(status.operatingMode),
                    variant = if (operatingModeIsStopped(status.operatingMode)) BadgeVariant.Destructive else BadgeVariant.Secondary
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String?, unit: String?, sub: String?, unknown: String, modifier: Modifier) {
    val c = IbTheme.colors
    val spoken = buildString {
        append(label).append(": ").append(value ?: unknown)
        if (value != null && unit != null) append(' ').append(unit)
        if (sub != null) append(", ").append(sub)
    }
    IbCard(modifier.clearAndSetSemantics { contentDescription = spoken }, padding = false) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
            if (value == null) {
                Text(unknown, style = MaterialTheme.typography.titleLarge, color = c.mutedForeground)
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value, style = TabularNumbers, color = c.foreground)
                    if (unit != null) Text(unit, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = c.mutedForeground)
        }
    }
}
