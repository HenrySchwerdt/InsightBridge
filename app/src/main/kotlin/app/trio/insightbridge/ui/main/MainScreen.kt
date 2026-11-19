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
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.LaunchedEffect
import app.trio.insightbridge.ui.components.IbAlert

/** Main screen after setup: warnings, alert, link health, last pump status, actions and log. */
@Composable
fun MainScreen(rt: BridgeRuntime, onRepairPump: () -> Unit, onRepairPhone: () -> Unit) {
    val health by rt.health.collectAsStateWithLifecycle()
    val status by rt.status.collectAsStateWithLifecycle()
    val logLines by rt.log.flow.collectAsStateWithLifecycle()
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var refreshing by remember { mutableStateOf(false) }
    var refreshFeedback by remember { mutableStateOf<Int?>(null) }
    var logOpen by remember { mutableStateOf(false) }
    var alertBusy by remember { mutableStateOf(false) }
    var alertFeedback by remember { mutableStateOf<Int?>(null) }
    // Result line of settings actions (unpair, link mode, restart...): every tap has a visible outcome.
    var note by remember { mutableStateOf<Int?>(null) }

    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            refreshFeedback = null
            val result = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { rt.refreshStatusNow() }
            refreshFeedback = when (result) {
                BridgeRuntime.RefreshResult.OK -> R.string.ui_refresh_ok
                BridgeRuntime.RefreshResult.NOT_PAIRED -> R.string.ui_refresh_not_paired
                BridgeRuntime.RefreshResult.UNREACHABLE, null -> R.string.ui_refresh_failed
                BridgeRuntime.RefreshResult.ERROR -> R.string.ui_refresh_error
            }
            refreshing = false
        }
    }

    fun alertAction(alert: AlertDto, confirm: Boolean) {
        if (alertBusy) return
        scope.launch {
            alertBusy = true
            alertFeedback = null
            val ok = withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                if (confirm) rt.userConfirmAlert(alert.alertId, alert.alertType) else rt.userSnoozeAlert(alert.alertId, alert.alertType)
            } ?: false
            alertFeedback = if (ok) R.string.ui_alert_done else R.string.ui_alert_failed
            if (ok) withTimeoutOrNull(REFRESH_TIMEOUT_MS) { rt.refreshStatusNow() }
            alertBusy = false
        }
    }

    // Opening the log appends a card below the fold: scroll it into view so the tap visibly does something.
    LaunchedEffect(logOpen) {
        if (logOpen) {
            kotlinx.coroutines.yield()
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 16.dp),
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
        note?.let { IbAlert(description = stringResource(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        HealthWarnings(rt, health.warnings) { note = it }
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
        ConnectionHealthCard(health, now)
        MainActions(rt, onRepairPump, onRepairPhone, logOpen, { logOpen = !logOpen }, onNote = { note = it })
        if (logOpen) LogCard(logLines, health)
        VersionLabel(onDeveloper = LocalOpenDeveloper.current)
    }
}

private const val REFRESH_TIMEOUT_MS = 45_000L

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
