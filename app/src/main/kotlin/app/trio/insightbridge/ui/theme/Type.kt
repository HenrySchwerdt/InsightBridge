/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.trio.insightbridge.R

/** Inter, bundled in res/font (OFL 1.1). Medium maps to the SemiBold file (Regular + SemiBold are bundled). */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_semibold, FontWeight.Bold)
)

private const val TNUM = "tnum"

/** Tabular figures for numbers that must not jitter. */
val TabularNumbers = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp, fontFeatureSettings = TNUM)

internal val IbTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = 2.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 1.sp),
    headlineLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.12).sp),
    headlineMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp),
    headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)

/** Monospace style for the log. */
internal val IbMono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp)
