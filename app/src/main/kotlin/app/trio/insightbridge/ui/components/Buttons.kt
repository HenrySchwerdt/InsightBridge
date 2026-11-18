/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.ui.theme.IbTheme

/**
 * shadcn button variants: Primary = default (fill), Secondary = outline, Muted = secondary (muted fill),
 * Danger = destructive, Text = ghost. Positive maps to the default fill (no green buttons).
 */
enum class ButtonStyle { Primary, Secondary, Muted, Danger, Positive, Text }

private val ButtonShape = RoundedCornerShape(8.dp)

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    enabled: Boolean = true
) {
    val c = IbTheme.colors
    val bg: Color
    val fg: Color
    var borderColor: Color? = null
    when (style) {
        ButtonStyle.Primary, ButtonStyle.Positive -> { bg = c.primary; fg = c.primaryForeground }
        ButtonStyle.Secondary -> { bg = Color.Transparent; fg = c.foreground; borderColor = c.border }
        ButtonStyle.Muted -> { bg = c.muted; fg = c.foreground }
        ButtonStyle.Danger -> { bg = c.destructive; fg = Color.White }
        ButtonStyle.Text -> { bg = Color.Transparent; fg = c.foreground }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(ButtonShape)
            .background(bg)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, ButtonShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, textAlign = TextAlign.Center)
    }
}

/** Small pill button for rows (e.g. "Allow", "Fix"). Keeps a 48dp touch target around a compact look. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false) {
    val c = IbTheme.colors
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(if (filled) c.primary else Color.Transparent)
            .border(1.dp, if (filled) c.primary else c.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (filled) c.primaryForeground else c.foreground)
    }
}
