/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.trio.insightbridge.ui.util.pumpLinkText
import app.trio.insightbridge.ui.components.BadgeVariant
import app.trio.insightbridge.ui.components.IbBadge
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trio.insightbridge.BridgeRuntime
import app.trio.insightbridge.R
import app.trio.insightbridge.protocol.AlertDto
import app.trio.insightbridge.ui.alerts.AlertCard
import app.trio.insightbridge.ui.components.BigButton
import app.trio.insightbridge.ui.components.ButtonStyle
import app.trio.insightbridge.ui.components.LocalOpenDeveloper
import app.trio.insightbridge.ui.components.VersionLabel
import app.trio.insightbridge.ui.theme.IbTheme
import app.trio.insightbridge.ui.util.rememberNow
import kotlinx.coroutines.launch

/** Main screen after setup: warnings, alert, link health, last pump status, actions and log. */
@Composable
fun MainScreen(rt: BridgeRuntime, onRepairPump: () -> Unit, onRepairPhone: () -> Unit) {
    val health by rt.health.collectAsStateWithLifecycle()
    val status by rt.status.collectAsStateWithLifecycle()
    val logLines by rt.log.flow.collectAsStateWithLifecycle()
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshFeedback by remember { mutableStateOf<Int?>(null) }
    var logOpen by remember { mutableStateOf(false) }
    var alertBusy by remember { mutableStateOf(false) }
    var alertFeedback by remember { mutableStateOf<Int?>(null) }

    fun refresh() {
        scope.launch {
            refreshing = true
            refreshFeedback = if (rt.refreshStatusNow()) R.string.ui_refresh_ok else R.string.ui_refresh_failed
            refreshing = false
        }
    }

    fun alertAction(alert: AlertDto, confirm: Boolean) {
        scope.launch {
            alertBusy = true
            val ok = if (confirm) rt.userConfirmAlert(alert.alertId, alert.alertType) else rt.userSnoozeAlert(alert.alertId, alert.alertType)
            alertFeedback = if (ok) R.string.ui_alert_done else R.string.ui_alert_failed
            if (ok) rt.refreshStatusNow()
            alertBusy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() }
                )
                Summary(problems = health.warnings.size, running = health.serviceRunning)
            }
            IbBadge(
                pumpLinkText(health.pumpLink).replaceFirstChar { it.uppercase() },
                variant = BadgeVariant.Outline,
                dot = pumpDot(health.pumpLink)
            )
        }
        HealthWarnings(rt, health.warnings)
        status?.activeAlert?.let { alert ->
            AlertCard(
                alert = alert,
                busy = alertBusy,
                feedback = alertFeedback?.let { stringResource(it) },
                onConfirm = { alertAction(alert, confirm = true) },
                onSnooze = { alertAction(alert, confirm = false) }
            )
        }
        StatusCard(status, now)
        BigButton(
            stringResource(if (refreshing) R.string.ui_refreshing else R.string.ui_refresh),
            { refresh() },
            style = ButtonStyle.Secondary,
            enabled = !refreshing
        )
        refreshFeedback?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = IbTheme.colors.mutedForeground, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        LinkCards(health, now)
        MainActions(rt, onRepairPump, onRepairPhone, logOpen, { logOpen = !logOpen })
        if (logOpen) LogCard(logLines)
        VersionLabel(onDeveloper = LocalOpenDeveloper.current)
    }
}

@Composable
private fun Summary(problems: Int, running: Boolean) {
    val c = IbTheme.colors
    val (text, color) = when {
        problems > 0 -> pluralStringResource(R.plurals.plural_problems, problems, problems) to c.danger
        !running -> stringResource(R.string.ui_summary_not_running) to c.attention
        else -> stringResource(R.string.ui_summary_ok) to c.mutedForeground
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
