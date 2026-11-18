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
import androidx.compose.runtime.remember
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
fun LogCard(lines: List<LogLine>, modifier: Modifier = Modifier) {
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
    }
}
