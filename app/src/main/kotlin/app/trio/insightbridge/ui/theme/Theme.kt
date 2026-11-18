/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** shadcn-style design tokens plus the semantic status colors. */
@Immutable
class IbColors(
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val mutedForeground: Color,
    val border: Color,
    val primary: Color,
    val primaryForeground: Color,
    val ring: Color,
    val destructive: Color,
    val dotOk: Color,
    val dotWarn: Color,
    val dotDanger: Color,
    // readable status text colors
    val ok: Color,
    val attention: Color,
    val danger: Color,
    // destructive alert surface
    val dangerSurface: Color,
    val onDangerSurface: Color,
    val isDark: Boolean
) {
    // Kept names used across screens. Containers are neutral: status is shown with dots, badges and alerts.
    val okContainer: Color get() = muted
    val onOkContainer: Color get() = foreground
    val attentionContainer: Color get() = muted
    val onAttentionContainer: Color get() = foreground
    val dangerContainer: Color get() = dangerSurface
    val onDangerContainer: Color get() = onDangerSurface
    val infoContainer: Color get() = muted
    val onInfoContainer: Color get() = foreground
    val info: Color get() = mutedForeground
    val alarm: Color get() = dangerSurface
    val onAlarm: Color get() = onDangerSurface
}

private val LightIb = IbColors(
    background = Palette.Background, foreground = Palette.Foreground, muted = Palette.Muted, mutedForeground = Palette.MutedForeground,
    border = Palette.Border, primary = Palette.Primary, primaryForeground = Palette.PrimaryForeground, ring = Palette.Ring,
    destructive = Palette.Destructive, dotOk = Palette.DotOk, dotWarn = Palette.DotWarn, dotDanger = Palette.DotDanger,
    ok = Palette.OkTextLight, attention = Palette.WarnTextLight, danger = Palette.DangerTextLight,
    dangerSurface = Palette.DangerSurfaceLight, onDangerSurface = Palette.OnDangerSurfaceLight, isDark = false
)

private val DarkIb = IbColors(
    background = Palette.BackgroundDark, foreground = Palette.ForegroundDark, muted = Palette.MutedDark, mutedForeground = Palette.MutedForegroundDark,
    border = Palette.BorderDark, primary = Palette.PrimaryDark, primaryForeground = Palette.PrimaryForegroundDark, ring = Palette.RingDark,
    destructive = Palette.Destructive, dotOk = Palette.DotOk, dotWarn = Palette.DotWarn, dotDanger = Palette.DotDanger,
    ok = Palette.OkTextDark, attention = Palette.WarnTextDark, danger = Palette.DangerTextDark,
    dangerSurface = Palette.DangerSurfaceDark, onDangerSurface = Palette.OnDangerSurfaceDark, isDark = true
)

/** Every Material3 slot is mapped to a shadcn token so no stock Material color leaks through. */
private fun scheme(c: IbColors, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = c.primary, onPrimary = c.primaryForeground,
    primaryContainer = c.muted, onPrimaryContainer = c.foreground,
    inversePrimary = c.primary,
    secondary = c.mutedForeground, onSecondary = c.background,
    secondaryContainer = c.muted, onSecondaryContainer = c.foreground,
    tertiary = c.mutedForeground, onTertiary = c.background,
    tertiaryContainer = c.muted, onTertiaryContainer = c.foreground,
    background = c.background, onBackground = c.foreground,
    surface = c.background, onSurface = c.foreground,
    surfaceVariant = c.muted, onSurfaceVariant = c.mutedForeground,
    surfaceTint = Color.Transparent,
    inverseSurface = c.foreground, inverseOnSurface = c.background,
    error = c.destructive, onError = Color.White,
    errorContainer = c.dangerSurface, onErrorContainer = c.onDangerSurface,
    outline = c.ring, outlineVariant = c.border,
    scrim = Color.Black,
    surfaceBright = c.background, surfaceDim = c.muted,
    surfaceContainerLowest = c.background, surfaceContainerLow = c.background,
    surfaceContainer = c.background, surfaceContainerHigh = c.muted, surfaceContainerHighest = c.muted
)

private val IbShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

private val LocalIbColors = staticCompositionLocalOf { LightIb }

object IbTheme {

    val colors: IbColors
        @Composable @ReadOnlyComposable get() = LocalIbColors.current
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightBridgeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) DarkIb else LightIb
    CompositionLocalProvider(
        LocalIbColors provides c,
        LocalRippleConfiguration provides RippleConfiguration(
            color = c.foreground,
            rippleAlpha = RippleAlpha(draggedAlpha = 0.08f, focusedAlpha = 0.08f, hoveredAlpha = 0.04f, pressedAlpha = 0.08f)
        )
    ) {
        MaterialTheme(colorScheme = scheme(c, darkTheme), typography = IbTypography, shapes = IbShapes, content = content)
    }
}
