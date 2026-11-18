/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.trio.insightbridge.R
import app.trio.insightbridge.engine.PhoneLinkState
import app.trio.insightbridge.protocol.PumpLinkState
import app.trio.insightbridge.protocol.StatusDto
import app.trio.insightbridge.protocol.WireMode
import app.trio.insightbridge.util.Format
import kotlinx.coroutines.delay

/** Ticking wall clock for "x ago" texts; updates once per second while composed. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun pumpLinkText(state: PumpLinkState): String = stringResource(
    when (state) {
        PumpLinkState.CONNECTED -> R.string.state_connected
        PumpLinkState.CONNECTING -> R.string.state_connecting
        PumpLinkState.DISCONNECTED -> R.string.state_disconnected
        PumpLinkState.NOT_PAIRED -> R.string.state_not_paired
    }
)

@Composable
fun phoneLinkText(paired: Boolean, state: PhoneLinkState): String = stringResource(
    if (!paired) R.string.state_not_paired else when (state) {
        PhoneLinkState.NONE -> R.string.ui_phone_none
        PhoneLinkState.HANDSHAKE -> R.string.state_connecting
        PhoneLinkState.SESSION -> R.string.state_connected
    }
)

/** "3m 04s ago" or "never" (localized wrapper around [Format.duration]). */
@Composable
fun agoText(now: Long, then: Long): String =
    if (then <= 0) stringResource(R.string.ui_never) else stringResource(R.string.ui_ago, Format.duration(now - then))

@Composable
fun operatingModeText(mode: String?): String = when (mode) {
    WireMode.STARTED -> stringResource(R.string.ui_mode_started)
    WireMode.STOPPED -> stringResource(R.string.ui_mode_stopped)
    WireMode.PAUSED -> stringResource(R.string.ui_mode_paused)
    else -> stringResource(R.string.ui_unknown)
}

/** True if the pump reports a mode that means "not delivering" (shown in amber). */
fun StatusDto.notDelivering(): Boolean = operatingModeIsStopped(operatingMode)

fun operatingModeIsStopped(mode: String?): Boolean = mode == WireMode.STOPPED || mode == WireMode.PAUSED
