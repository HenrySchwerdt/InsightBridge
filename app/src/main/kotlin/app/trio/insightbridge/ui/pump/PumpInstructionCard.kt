/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.pump

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.ui.theme.IbTheme

/** Everything one pump-side step shows. All text is resource based. */
data class PumpStepContent(
    val breadcrumbRes: Int?,
    val youSeeRes: Int,
    val screenTitleRes: Int,
    val screenLineRes: List<Int>,
    val highlight: PumpButton?
)

/** Pump-side step: menu path chips, the schematic pump (hero), a quiet caption and a compact "You should see". */
@Composable
fun PumpInstructionCard(content: PumpStepContent, modifier: Modifier = Modifier) {
    val lines = buildList {
        add(stringResource(content.screenTitleRes))
        content.screenLineRes.forEach { add(stringResource(it)) }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        content.breadcrumbRes?.let { Breadcrumb(stringResource(it)) }
        InsightPumpIllustration(screenLines = lines, highlight = content.highlight)
        Text(
            stringResource(R.string.ui_pump_schematic),
            style = MaterialTheme.typography.labelSmall,
            color = IbTheme.colors.mutedForeground,
            textAlign = TextAlign.Center
        )
        YouShouldSee(stringResource(content.youSeeRes))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Breadcrumb(path: String) {
    val c = IbTheme.colors
    val parts = path.split("→").map { it.trim() }.filter { it.isNotEmpty() }
    val label = stringResource(R.string.ui_menu_path)
    FlowRow(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label + ": " + parts.joinToString(", ") },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        parts.forEachIndexed { i, part ->
            if (i > 0) Text("›", style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
            Text(
                part,
                style = MaterialTheme.typography.labelMedium,
                color = c.foreground,
                modifier = Modifier
                    .background(c.muted, RoundedCornerShape(50))
                    .border(1.dp, c.border, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun YouShouldSee(text: String) {
    val c = IbTheme.colors
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(stringResource(R.string.ui_you_should_see), style = MaterialTheme.typography.labelMedium, color = c.foreground, modifier = Modifier.semantics { heading() })
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
    }
}
