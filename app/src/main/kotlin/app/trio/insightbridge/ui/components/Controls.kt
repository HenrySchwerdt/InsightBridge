/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.ui.theme.IbTheme

/** Neutral shadcn-style switch (state is owned by the surrounding toggleable row, so no click handler here). */
@Composable
fun IbSwitch(checked: Boolean, enabled: Boolean = true) {
    val c = IbTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.background, checkedTrackColor = c.primary, checkedBorderColor = c.primary,
            uncheckedThumbColor = c.background, uncheckedTrackColor = c.ring, uncheckedBorderColor = c.ring,
            disabledCheckedThumbColor = c.background, disabledCheckedTrackColor = c.mutedForeground, disabledCheckedBorderColor = c.mutedForeground,
            disabledUncheckedThumbColor = c.background, disabledUncheckedTrackColor = c.border, disabledUncheckedBorderColor = c.border
        )
    )
}

/** 48dp back arrow (icon only, labelled for TalkBack). */
@Composable
fun BackArrow(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onBack),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_back), tint = IbTheme.colors.foreground)
    }
}
