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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.engine.PhoneLinkState
import app.trio.insightbridge.protocol.PumpLinkState
import app.trio.insightbridge.service.HealthSnapshot
import app.trio.insightbridge.ui.components.BadgeVariant
import app.trio.insightbridge.ui.components.CardHeader
import app.trio.insightbridge.ui.components.IbBadge
import app.trio.insightbridge.ui.components.IbCard
import app.trio.insightbridge.ui.components.Separator
import app.trio.insightbridge.ui.theme.IbTheme
import app.trio.insightbridge.ui.util.agoText
import app.trio.insightbridge.ui.util.phoneLinkText
import app.trio.insightbridge.ui.util.pumpLinkText
import app.trio.insightbridge.util.Format

@Composable
internal fun pumpDot(s: PumpLinkState): Color {
    val c = IbTheme.colors
    return when (s) {
        PumpLinkState.CONNECTED -> c.dotOk
        PumpLinkState.CONNECTING, PumpLinkState.DISCONNECTED -> c.dotWarn
        PumpLinkState.NOT_PAIRED -> c.dotDanger
    }
}

/** One "Links" card with the pump row, the iPhone row and the uptime, separated by hairlines. */
@Composable
fun LinkCards(health: HealthSnapshot, now: Long, modifier: Modifier = Modifier) {
    val c = IbTheme.colors
    val phoneDot = when {
        !health.phonePaired -> c.dotDanger
        health.phoneLink == PhoneLinkState.SESSION -> c.dotOk
        else -> c.dotWarn
    }
    IbCard(modifier, padding = false) {
        CardHeader(stringResource(R.string.ui_links_title), Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp))
        LinkRow(
            stringResource(R.string.ui_pump_link),
            stringResource(R.string.ui_last_pump_contact) + ": " + agoText(now, health.lastPumpContact),
            pumpLinkText(health.pumpLink), pumpDot(health.pumpLink)
        )
        if (health.simulated) {
            Text(
                stringResource(R.string.ui_link_simulated),
                style = MaterialTheme.typography.bodySmall,
                color = c.danger,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            )
        }
        Separator()
        LinkRow(
            stringResource(R.string.ui_phone_link),
            stringResource(R.string.ui_last_phone_request) + ": " + agoText(now, health.lastPhoneRequest),
            phoneLinkText(health.phonePaired, health.phoneLink), phoneDot
        )
        Separator()
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.ui_uptime), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
            Text(
                if (health.serviceStartedAt == 0L) stringResource(R.string.ui_unknown) else Format.duration((now - health.serviceStartedAt).coerceAtLeast(0)),
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, detail: String, state: String, dot: Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = IbTheme.colors.mutedForeground)
        }
        IbBadge(state.replaceFirstChar { it.uppercase() }, variant = BadgeVariant.Outline, dot = dot)
    }
}
