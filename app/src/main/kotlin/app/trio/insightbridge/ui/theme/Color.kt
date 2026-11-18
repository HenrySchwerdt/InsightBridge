/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.theme

import androidx.compose.ui.graphics.Color

/** shadcn/ui "zinc" tokens. Status colors are used only for small dots, badges and alert accents. */
internal object Palette {

    // Light
    val Background = Color(0xFFFFFFFF)
    val Foreground = Color(0xFF09090B)
    val Muted = Color(0xFFF4F4F5)
    val MutedForeground = Color(0xFF71717A)
    val Border = Color(0xFFE4E4E7)
    val Primary = Color(0xFF18181B)
    val PrimaryForeground = Color(0xFFFAFAFA)
    val Ring = Color(0xFFA1A1AA)
    val Destructive = Color(0xFFEF4444)

    // Dark
    val BackgroundDark = Color(0xFF09090B)
    val ForegroundDark = Color(0xFFFAFAFA)
    val MutedDark = Color(0xFF27272A)
    val MutedForegroundDark = Color(0xFFA1A1AA)
    val BorderDark = Color(0xFF27272A)
    val PrimaryDark = Color(0xFFFAFAFA)
    val PrimaryForegroundDark = Color(0xFF18181B)
    val RingDark = Color(0xFF71717A)

    // Status dots / badges
    val DotOk = Color(0xFF22C55E)
    val DotWarn = Color(0xFFF59E0B)
    val DotDanger = Color(0xFFEF4444)

    // Readable status text colors (AA on the matching background)
    val OkTextLight = Color(0xFF15803D)
    val OkTextDark = Color(0xFF4ADE80)
    val WarnTextLight = Color(0xFFB45309)
    val WarnTextDark = Color(0xFFFBBF24)
    val DangerTextLight = Color(0xFFB91C1C)
    val DangerTextDark = Color(0xFFF87171)

    // Destructive alert surface
    val DangerSurfaceLight = Color(0xFFFEF2F2)
    val OnDangerSurfaceLight = Color(0xFF7F1D1D)
    val DangerSurfaceDark = Color(0xFF2A1313)
    val OnDangerSurfaceDark = Color(0xFFFECACA)
}
