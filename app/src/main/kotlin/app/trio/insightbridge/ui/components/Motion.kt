/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.components

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** False when the user disabled animations (Settings.Global.ANIMATOR_DURATION_SCALE == 0). */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val ctx = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } catch (_: Throwable) {
            true
        }
    }
}
