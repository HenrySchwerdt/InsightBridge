/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.ui.theme.IbTheme

private val CardShape = RoundedCornerShape(12.dp)

/** shadcn Card: 1dp border, 12dp radius, no shadow, 20dp padding. */
@Composable
fun IbCard(
    modifier: Modifier = Modifier,
    borderColor: Color = IbTheme.colors.border,
    background: Color = IbTheme.colors.background,
    padding: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(background, CardShape)
            .border(1.dp, borderColor, CardShape)
            .then(if (padding) Modifier.padding(20.dp) else Modifier),
        verticalArrangement = if (padding) Arrangement.spacedBy(12.dp) else Arrangement.Top
    ) { content() }
}

/** Card title with a muted description. */
@Composable
fun CardHeader(title: String, modifier: Modifier = Modifier, description: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = IbTheme.colors.foreground, modifier = Modifier.semantics { heading() })
        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = IbTheme.colors.mutedForeground)
    }
}

/**
 * Compatibility wrapper used by older call sites: a bordered card. A destructive [rule] gives a red
 * border and the destructive surface; nothing else is colored.
 */
@Composable
fun RuledCard(
    modifier: Modifier = Modifier,
    rule: Color = Color.Unspecified,
    container: Color = Color.Unspecified,
    content: @Composable () -> Unit
) {
    val c = IbTheme.colors
    val destructive = rule == c.danger
    IbCard(
        modifier = modifier,
        borderColor = if (destructive) c.destructive else c.border,
        background = if (destructive) c.dangerSurface else c.background
    ) { content() }
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = IbTheme.colors.foreground,
        modifier = modifier.semantics { heading() }
    )
}

@Composable
fun Separator(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(IbTheme.colors.border))
}

/** "What happens next": a quiet muted block, never a competing card. */
@Composable
fun WhatHappensNext(text: String, modifier: Modifier = Modifier) {
    val c = IbTheme.colors
    Column(
        modifier.fillMaxWidth().background(c.muted, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            stringResource(R.string.ui_what_next),
            style = MaterialTheme.typography.labelMedium,
            color = c.foreground,
            modifier = Modifier.semantics { heading() }
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
    }
}

enum class BadgeVariant { Default, Secondary, Outline, Destructive }

/** shadcn Badge: small pill, optional status dot (the dot color carries status, the text always says it). */
@Composable
fun IbBadge(text: String, modifier: Modifier = Modifier, variant: BadgeVariant = BadgeVariant.Outline, dot: Color? = null) {
    val c = IbTheme.colors
    val shape = RoundedCornerShape(50)
    val bg: Color
    val fg: Color
    val border: Color
    when (variant) {
        BadgeVariant.Default -> { bg = c.primary; fg = c.primaryForeground; border = c.primary }
        BadgeVariant.Secondary -> { bg = c.muted; fg = c.foreground; border = c.muted }
        BadgeVariant.Outline -> { bg = Color.Transparent; fg = c.foreground; border = c.border }
        BadgeVariant.Destructive -> { bg = c.dangerSurface; fg = c.onDangerSurface; border = c.destructive }
    }
    Row(
        modifier.background(bg, shape).border(1.dp, border, shape).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Old name kept for call sites; renders an outline badge. */
@Composable
fun StatePill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    IbBadge(text, modifier, BadgeVariant.Outline, dot = content)
}

/** shadcn Alert: bordered box with icon, title and description; destructive variant is red. */
@Composable
fun IbAlert(
    description: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    destructive: Boolean = false,
    strong: Boolean = false,
    icon: ImageVector = if (destructive) Icons.Filled.Warning else Icons.Filled.Info,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val c = IbTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val fg = if (destructive) c.onDangerSurface else c.foreground
    Row(
        modifier
            .fillMaxWidth()
            .background(if (destructive) c.dangerSurface else c.background, shape)
            .border(1.dp, if (destructive) c.destructive else c.border, shape)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (destructive) c.destructive else c.foreground, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = fg, modifier = Modifier.semantics { heading() })
            Text(description, style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall, color = if (destructive) fg else c.mutedForeground)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = IbTheme.colors.foreground) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = IbTheme.colors.mutedForeground)
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
    }
}
