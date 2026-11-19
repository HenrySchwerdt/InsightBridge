/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.service.HealthSnapshot
import app.trio.insightbridge.ui.components.BadgeVariant
import app.trio.insightbridge.ui.components.CardHeader
import app.trio.insightbridge.ui.components.IbBadge
import app.trio.insightbridge.ui.components.IbCard
import app.trio.insightbridge.ui.components.Separator
import app.trio.insightbridge.ui.theme.IbTheme
import app.trio.insightbridge.ui.util.agoText

/**
 * Compact "Connection health": is the bridge visible to the iPhone, how many sessions in the last 24 h (many
 * sessions = the link keeps dropping), and why the last one ended. Live from the health flow.
 */
@Composable
fun ConnectionHealthCard(health: HealthSnapshot, now: Long, modifier: Modifier = Modifier) {
    val c = IbTheme.colors
    IbCard(modifier, padding = false) {
        CardHeader(stringResource(R.string.ui_health_title), Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp))
        HealthRow(stringResource(R.string.ui_health_advertising)) {
            IbBadge(
                stringResource(if (health.advertising) R.string.ui_on else R.string.ui_off),
                variant = BadgeVariant.Outline,
                dot = if (health.advertising) c.dotOk else c.dotDanger
            )
        }
        Separator()
        HealthRow(stringResource(R.string.ui_health_sessions)) {
            Text(health.sessions24h.toString(), style = MaterialTheme.typography.titleSmall)
        }
        Separator()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.ui_health_last_disc), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
            Text(
                if (health.lastDisconnectReason == null) stringResource(R.string.ui_health_last_disc_none)
                else health.lastDisconnectReason + " (" + agoText(now, health.lastDisconnectAt) + ")",
                style = MaterialTheme.typography.bodyMedium
            )
            if (health.advertiseError != 0) {
                Text(stringResource(R.string.ui_health_adv_error, health.advertiseError), style = MaterialTheme.typography.bodySmall, color = c.danger)
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = IbTheme.colors.mutedForeground, modifier = Modifier.weight(1f))
        trailing()
    }
}
