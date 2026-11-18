/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.BuildConfig
import app.trio.insightbridge.R

/** Opens the hidden developer screen; provided by the app root so every screen's version label can reach it. */
val LocalOpenDeveloper = compositionLocalOf<() -> Unit> { {} }

private const val TAPS_TO_OPEN = 7
private const val TAP_WINDOW_MS = 4_000L

/** Version text; tapping it [TAPS_TO_OPEN] times (within a few seconds) opens the hidden developer screen. */
@Composable
fun VersionLabel(onDeveloper: () -> Unit, modifier: Modifier = Modifier) {
    var taps by remember { mutableIntStateOf(0) }
    var firstTap by remember { mutableLongStateOf(0L) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.ui_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) {
                    val now = System.currentTimeMillis()
                    if (taps == 0 || now - firstTap > TAP_WINDOW_MS) { taps = 0; firstTap = now }
                    taps++
                    if (taps >= TAPS_TO_OPEN) { taps = 0; onDeveloper() }
                }
                .semantics { role = Role.Button }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}
