/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.pump

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.trio.insightbridge.R
import app.trio.insightbridge.ui.components.rememberAnimationsEnabled
import app.trio.insightbridge.ui.theme.IbTheme
import app.trio.insightbridge.ui.theme.Inter

/** Generic, schematic buttons. Not the real Insight layout and no manufacturer artwork. */
enum class PumpButton { BACK, UP, DOWN, CONFIRM }

private fun PumpButton.labelRes(): Int = when (this) {
    PumpButton.BACK -> R.string.ui_btn_back
    PumpButton.UP -> R.string.ui_btn_up
    PumpButton.DOWN -> R.string.ui_btn_down
    PumpButton.CONFIRM -> R.string.ui_btn_confirm
}

private fun PumpButton.glyph(): String = when (this) {
    PumpButton.BACK -> "←"
    PumpButton.UP -> "▲"
    PumpButton.DOWN -> "▼"
    PumpButton.CONFIRM -> "✓"
}

/**
 * Own-drawn line-art pump: 1.5dp strokes in the foreground color, a screen with monospace text and
 * round buttons. [highlight] is a filled circle with a pulsing ring and a "Press ✓" label (static when
 * animations are off). TalkBack gets a spoken description instead of the drawing.
 */
@Composable
fun InsightPumpIllustration(
    screenLines: List<String>,
    highlight: PumpButton?,
    modifier: Modifier = Modifier
) {
    val c = IbTheme.colors
    val buttonName = highlight?.let { stringResource(it.labelRes()) }
    val description = buildString {
        append(stringResource(R.string.ui_pump_illu_desc, screenLines.joinToString(". ")))
        if (buttonName != null) append(' ').append(stringResource(R.string.ui_pump_illu_highlight, buttonName))
    }
    Column(
        modifier = modifier
            .widthIn(min = 200.dp, max = 240.dp)
            .fillMaxWidth(0.62f)
            .clearAndSetSemantics { contentDescription = description }
            .drawBehind {
                drawRoundRect(c.foreground, cornerRadius = CornerRadius(28.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            }
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.5.dp, c.foreground, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            screenLines.take(3).forEachIndexed { i, line ->
                Text(
                    line,
                    color = c.foreground,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
        ButtonPad(highlight)
    }
}

@Composable
private fun ButtonPad(highlight: PumpButton?) {
    val c = IbTheme.colors
    val animate = rememberAnimationsEnabled()
    val measurer = rememberTextMeasurer()
    val pressLabel = if (highlight != null) stringResource(R.string.ui_press_fmt, highlight.glyph()) else ""
    val pulse = if (animate && highlight != null) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "pulseValue"
        ).value
    } else {
        0.35f
    }
    val labelStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = c.primaryForeground)
    Canvas(Modifier.fillMaxWidth().height(if (highlight != null) 118.dp else 84.dp)) {
        val r = minOf(size.width * 0.10f, 17.dp.toPx())
        val rowH = 84.dp.toPx()
        val centers = mapOf(
            PumpButton.BACK to Offset(size.width * 0.14f, rowH * 0.5f),
            PumpButton.CONFIRM to Offset(size.width * 0.5f, rowH * 0.5f),
            PumpButton.UP to Offset(size.width * 0.86f, rowH * 0.27f),
            PumpButton.DOWN to Offset(size.width * 0.86f, rowH * 0.73f)
        )
        centers.forEach { (b, center) ->
            val radius = if (b == PumpButton.CONFIRM) r * 1.3f else r
            val on = b == highlight
            if (on) {
                drawCircle(c.foreground.copy(alpha = 0.10f), radius + 5.dp.toPx(), center)
                drawCircle(c.foreground.copy(alpha = (1f - pulse) * 0.45f), radius + 5.dp.toPx() + pulse * 12.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                drawCircle(c.foreground, radius, center)
            } else {
                drawCircle(c.foreground, radius, center, style = Stroke(1.5.dp.toPx()))
            }
            drawGlyph(b, center, radius, if (on) c.background else c.foreground)
        }
        if (highlight != null) {
            val center = centers.getValue(highlight)
            val m = measurer.measure(pressLabel, labelStyle)
            val padX = 10.dp.toPx()
            val padY = 4.dp.toPx()
            val w = m.size.width + padX * 2
            val h = m.size.height + padY * 2
            val x = (center.x - w / 2).coerceIn(0f, size.width - w)
            val y = rowH + 8.dp.toPx()
            drawRoundRect(c.foreground, Offset(x, y), Size(w, h), CornerRadius(h / 2))
            drawText(m, topLeft = Offset(x + padX, y + padY))
        }
    }
}

private fun DrawScope.drawGlyph(b: PumpButton, center: Offset, radius: Float, color: Color) {
    val s = radius * 0.42f
    val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val p = Path()
    when (b) {
        PumpButton.UP -> { p.moveTo(center.x - s, center.y + s * 0.5f); p.lineTo(center.x, center.y - s * 0.6f); p.lineTo(center.x + s, center.y + s * 0.5f) }
        PumpButton.DOWN -> { p.moveTo(center.x - s, center.y - s * 0.5f); p.lineTo(center.x, center.y + s * 0.6f); p.lineTo(center.x + s, center.y - s * 0.5f) }
        PumpButton.BACK -> { p.moveTo(center.x + s, center.y); p.lineTo(center.x - s, center.y); p.moveTo(center.x - s * 0.1f, center.y - s * 0.8f); p.lineTo(center.x - s, center.y); p.lineTo(center.x - s * 0.1f, center.y + s * 0.8f) }
        PumpButton.CONFIRM -> { p.moveTo(center.x - s, center.y); p.lineTo(center.x - s * 0.3f, center.y + s * 0.7f); p.lineTo(center.x + s, center.y - s * 0.7f) }
    }
    drawPath(p, color, style = stroke)
}
