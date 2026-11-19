/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.trio.insightbridge.service.HealthSnapshot
import app.trio.insightbridge.ui.components.BigButton
import app.trio.insightbridge.ui.components.ButtonStyle
import app.trio.insightbridge.util.LogShare
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.ui.components.CardHeader
import app.trio.insightbridge.ui.components.IbCard
import app.trio.insightbridge.ui.theme.IbMono
import app.trio.insightbridge.ui.theme.IbTheme
import app.trio.insightbridge.util.LogBuffer
import app.trio.insightbridge.util.LogLine

/** Read-only log, newest line at the bottom. The core never writes secrets into it. */
@Composable
fun LogCard(lines: List<LogLine>, health: HealthSnapshot, modifier: Modifier = Modifier) {
    val text = remember(lines) { lines.joinToString("\n") { LogBuffer.format(it) } }
    val scroll = rememberScrollState()
    val c = IbTheme.colors
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    IbCard(modifier) {
        CardHeader(stringResource(R.string.ui_log_title), description = stringResource(R.string.ui_log_note))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .background(c.muted, RoundedCornerShape(8.dp))
                .verticalScroll(scroll)
                .padding(12.dp)
        ) {
            Text(text.ifEmpty { stringResource(R.string.ui_log_empty) }, style = IbMono, color = c.foreground)
        }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var feedback by remember { mutableStateOf<Int?>(null) }
        BigButton(
            stringResource(R.string.ui_share_log),
            { feedback = if (LogShare.share(ctx, lines, LogShare.healthSummary(health))) null else R.string.ui_log_share_failed },
            style = ButtonStyle.Secondary
        )
        BigButton(
            stringResource(R.string.ui_copy_log),
            { feedback = if (LogShare.copy(ctx, lines, LogShare.healthSummary(health))) R.string.ui_log_copied else R.string.ui_log_copy_failed },
            style = ButtonStyle.Secondary
        )
        feedback?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
