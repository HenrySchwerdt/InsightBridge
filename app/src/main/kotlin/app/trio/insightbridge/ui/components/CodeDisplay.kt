/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.ui.theme.IbTheme

/** A verification code, huge and monospace. TalkBack reads it character by character. */
@Composable
fun BigCode(
    display: String,
    spoken: String,
    caption: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge
) {
    IbCard(modifier.clearAndSetSemantics { contentDescription = "$caption: $spoken" }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = IbTheme.colors.mutedForeground)
            Text(
                display,
                style = style,
                color = IbTheme.colors.foreground,
                textAlign = TextAlign.Center,
                softWrap = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
