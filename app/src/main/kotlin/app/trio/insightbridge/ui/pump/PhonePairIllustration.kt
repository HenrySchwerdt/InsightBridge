/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.pump

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.ui.theme.IbTheme

/** Minimal line art: the iPhone and this phone side by side with a dotted link. Decorative (no TalkBack content). */
@Composable
fun PhonePairIllustration(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PhoneIcon(stringResource(R.string.ui_iphone), notch = true)
        Dots()
        PhoneIcon(stringResource(R.string.ui_this_phone), notch = false)
    }
}

@Composable
private fun PhoneIcon(label: String, notch: Boolean) {
    val c = IbTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(width = 64.dp, height = 112.dp)) {
            val sw = 1.5.dp.toPx()
            drawRoundRect(c.foreground, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(14.dp.toPx()), style = Stroke(sw))
            if (notch) {
                drawLine(c.foreground, Offset(size.width * 0.38f, 11.dp.toPx()), Offset(size.width * 0.62f, 11.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            } else {
                drawCircle(c.foreground, 2.5.dp.toPx(), Offset(size.width / 2, 11.dp.toPx()))
            }
            drawLine(c.foreground, Offset(size.width * 0.38f, size.height - 11.dp.toPx()), Offset(size.width * 0.62f, size.height - 11.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.mutedForeground)
    }
}

@Composable
private fun Dots() {
    val c = IbTheme.colors
    Canvas(Modifier.width(64.dp).height(112.dp)) {
        val y = size.height * 0.45f
        for (i in 0..2) drawCircle(c.foreground.copy(alpha = 0.25f + 0.25f * i), 3.dp.toPx(), Offset(size.width * (0.25f + 0.25f * i), y))
    }
}
