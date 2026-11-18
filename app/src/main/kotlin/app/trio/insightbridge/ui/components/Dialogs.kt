/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.ui.theme.IbTheme

/** Confirmation dialog for consequential actions. Cancel is the safe default. */
@Composable
fun IbConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    val c = IbTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.background,
        tonalElevation = 0.dp,
        shape = MaterialTheme.shapes.large,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = c.mutedForeground) },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(confirmLabel, onConfirm, style = if (destructive) ButtonStyle.Danger else ButtonStyle.Primary)
                BigButton(stringResource(R.string.ui_cancel), onDismiss, style = ButtonStyle.Secondary)
            }
        }
    )
}

/** Dialog that offers several actions as buttons plus Cancel. */
@Composable
fun IbChoiceDialog(
    title: String,
    text: String,
    options: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    val c = IbTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.background,
        tonalElevation = 0.dp,
        shape = MaterialTheme.shapes.large,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = c.mutedForeground) },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (label, action) -> BigButton(label, action, style = ButtonStyle.Secondary) }
                BigButton(stringResource(R.string.ui_cancel), onDismiss, style = ButtonStyle.Text)
            }
        }
    )
}
