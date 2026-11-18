/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.ui.theme.IbTheme

/** Persistent destructive alert on EVERY screen while the simulated pump is active. */
@Composable
fun SimulatorBanner(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(IbTheme.colors.background)
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) {
                heading()
                liveRegion = LiveRegionMode.Assertive
            }
    ) {
        IbAlert(description = stringResource(R.string.sim_banner), destructive = true, strong = true)
    }
}

/** Flat destructive alert for a health problem, with a small Fix button. */
@Composable
fun WarningBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    IbAlert(
        description = message,
        title = stringResource(R.string.ui_warning_label),
        destructive = true,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        if (actionLabel != null && onAction != null) {
            BigButton(actionLabel, onAction, style = ButtonStyle.Secondary)
        }
    }
}
