/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.ui.theme.IbTheme

/** One troubleshooting entry: a question-like title and a how-to-fix body. */
data class TroubleTopic(val titleRes: Int, val bodyRes: Int)

/**
 * Collapsed "Need help?" row (shadcn collapsible in a bordered card). Topics expand individually;
 * [highlight] is opened initially, e.g. the topic matching a failure.
 */
@Composable
fun TroubleshootSection(
    topics: List<TroubleTopic>,
    modifier: Modifier = Modifier,
    highlight: TroubleTopic? = null,
    key: String = "ts"
) {
    if (topics.isEmpty()) return
    var open by rememberSaveable(key) { mutableStateOf(highlight != null) }
    val expanded = stringResource(R.string.ui_expanded)
    val collapsed = stringResource(R.string.ui_collapsed)
    val c = IbTheme.colors
    IbCard(modifier, padding = false) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(onClickLabel = stringResource(R.string.ui_ts_toggle)) { open = !open }
                .semantics { role = Role.Button; stateDescription = if (open) expanded else collapsed }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.ui_ts_title),
                style = MaterialTheme.typography.titleMedium,
                color = c.foreground,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            Text(if (open) "−" else "+", style = MaterialTheme.typography.titleLarge, color = c.mutedForeground)
        }
        if (open) {
            topics.forEach { t ->
                Separator()
                TopicRow(t, startOpen = t == highlight, key = "$key/${t.titleRes}")
            }
        }
    }
}

@Composable
private fun TopicRow(t: TroubleTopic, startOpen: Boolean, key: String) {
    var open by rememberSaveable(key) { mutableStateOf(startOpen) }
    val expanded = stringResource(R.string.ui_expanded)
    val collapsed = stringResource(R.string.ui_collapsed)
    val c = IbTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.ui_ts_toggle)) { open = !open }
            .semantics { role = Role.Button; stateDescription = if (open) expanded else collapsed }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(t.titleRes), style = MaterialTheme.typography.titleSmall, color = c.foreground)
        if (open) Text(stringResource(t.bodyRes), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
    }
}
