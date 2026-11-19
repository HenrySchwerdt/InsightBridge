/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trio.insightbridge.R
import app.trio.insightbridge.engine.PairingController
import app.trio.insightbridge.engine.PairingUiState
import app.trio.insightbridge.service.BridgeService
import app.trio.insightbridge.ui.components.BigButton
import app.trio.insightbridge.ui.components.IbAlert
import app.trio.insightbridge.ui.components.BigCode
import app.trio.insightbridge.ui.components.ButtonStyle
import app.trio.insightbridge.ui.components.RuledCard
import app.trio.insightbridge.ui.components.IbCard
import app.trio.insightbridge.ui.components.TroubleshootSection
import app.trio.insightbridge.ui.components.WhatHappensNext
import app.trio.insightbridge.ui.theme.IbTheme
import app.trio.insightbridge.ui.pump.PhonePairIllustration
import app.trio.insightbridge.ui.util.CodeFormat
import app.trio.insightbridge.ui.util.PhoneFailureKind

private const val STEP = 3
private const val TOTAL = 4

/** Step 3: pair the iPhone (Trio). Driven entirely by [PairingController.state]. */
@Composable
fun PhoneStep(
    pairing: PairingController,
    phonePaired: Boolean,
    onBack: (() -> Unit)?,
    resetStale: Boolean,
    onFinished: () -> Unit,
    bridgeVisible: Boolean = true
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Opening the pairing window needs the bridge service (it owns the Bluetooth side): make sure it runs.
    val openWindow = {
        BridgeService.start(ctx)
        pairing.open()
    }
    val state by pairing.state.collectAsStateWithLifecycle()
    // Stale Done/Failed from an earlier session must not be shown as fresh results.
    LaunchedEffect(Unit) {
        val s = pairing.state.value
        if (resetStale && (s is PairingUiState.Done || s is PairingUiState.Failed)) pairing.cancel()
    }
    val back: (() -> Unit)? = onBack?.let { b -> { pairing.cancel(); b() } }
    when (val s = state) {
        PairingUiState.Idle -> if (phonePaired) AlreadyPaired(pairing, openWindow, back, onFinished) else Intro(openWindow, back)
        is PairingUiState.Waiting -> Waiting(pairing, s.secondsLeft, bridgeVisible, back)
        is PairingUiState.ConfirmCode -> Confirm(pairing, s)
        PairingUiState.Done -> Done(onFinished)
        is PairingUiState.Failed -> Failed(openWindow, s.reason, back)
    }
}

@Composable
private fun Intro(openWindow: () -> Unit, onBack: (() -> Unit)?) {
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_phone_title), onBack = onBack,
        actions = { BigButton(stringResource(R.string.wz_phone_pair_button), openWindow) },
        footer = { TroubleshootSection(Troubles.phoneAll, key = "ts-phone-intro") }
    ) {
        Text(stringResource(R.string.wz_phone_intro), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)
        PhonePairIllustration(Modifier.padding(vertical = 16.dp))
        TrioSteps()
        WhatHappensNext(stringResource(R.string.wz_phone_next))
    }
}

@Composable
private fun AlreadyPaired(pairing: PairingController, openWindow: () -> Unit, onBack: (() -> Unit)?, onFinished: () -> Unit) {
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_phone_already_title), onBack = onBack,
        actions = {
            BigButton(stringResource(R.string.ui_next), onFinished)
            BigButton(stringResource(R.string.wz_phone_pair_again), openWindow, style = ButtonStyle.Secondary)
        },
        footer = { TroubleshootSection(Troubles.phoneAll, key = "ts-phone-already") }
    ) {
        RuledCard(rule = IbTheme.colors.ok) { Text(stringResource(R.string.wz_phone_already_body), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground) }
        WhatHappensNext(stringResource(R.string.wz_phone_already_next))
    }
}

@Composable
private fun TrioSteps() {
    IbCard {
        Text(stringResource(R.string.wz_phone_trio_head), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.wz_phone_trio_steps), style = MaterialTheme.typography.bodyMedium, color = IbTheme.colors.mutedForeground)
    }
}

@Composable
private fun Waiting(pairing: PairingController, secondsLeft: Int, bridgeVisible: Boolean, onBack: (() -> Unit)?) {
    val total = (PairingController.WINDOW_MS / 1000).toInt()
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_phone_wait_title), onBack = onBack,
        actions = { BigButton(stringResource(R.string.ui_cancel), { pairing.cancel() }, style = ButtonStyle.Secondary) },
        footer = { TroubleshootSection(Troubles.phoneAll, key = "ts-phone-wait", highlight = null) }
    ) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                pluralStringResource(R.plurals.plural_seconds_left, secondsLeft, secondsLeft),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            LinearProgressIndicator(
                progress = { (secondsLeft.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = IbTheme.colors.foreground,
                trackColor = IbTheme.colors.muted,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        }
        if (!bridgeVisible) IbAlert(description = stringResource(R.string.wz_phone_not_visible), destructive = true)
        PhonePairIllustration(Modifier.padding(vertical = 8.dp))
        TrioSteps()
        WhatHappensNext(stringResource(R.string.wz_phone_wait_next))
    }
}

@Composable
private fun Confirm(pairing: PairingController, s: PairingUiState.ConfirmCode) {
    val grouped = CodeFormat.groupSas(s.code)
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_phone_code_title), onBack = null,
        actions = {
            BigButton(stringResource(R.string.wz_code_same), { pairing.userConfirm() }, style = ButtonStyle.Positive, enabled = !s.userConfirmed)
            BigButton(stringResource(R.string.wz_code_different), { pairing.userReject() }, style = ButtonStyle.Secondary)
        },
        footer = { TroubleshootSection(Troubles.phoneAll, key = "ts-phone-code", highlight = null) }
    ) {
        Text(stringResource(R.string.wz_phone_code_body), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)
        BigCode(display = grouped, spoken = CodeFormat.spoken(grouped), caption = stringResource(R.string.wz_phone_code_caption))
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (s.phoneConfirmed) R.string.wz_phone_iphone_confirmed else R.string.wz_phone_iphone_waiting),
                style = MaterialTheme.typography.titleSmall,
                color = if (s.phoneConfirmed) IbTheme.colors.ok else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (s.userConfirmed) Text(stringResource(R.string.wz_phone_you_confirmed), style = MaterialTheme.typography.titleSmall)
        }
        WhatHappensNext(stringResource(R.string.wz_phone_code_next))
    }
}

@Composable
private fun Done(onFinished: () -> Unit) {
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(R.string.wz_phone_done_title), onBack = null,
        actions = { BigButton(stringResource(R.string.ui_next), onFinished) }
    ) {
        RuledCard(rule = IbTheme.colors.ok, container = IbTheme.colors.okContainer) {
            Text(stringResource(R.string.wz_phone_done_body), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.onOkContainer)
        }
        WhatHappensNext(stringResource(R.string.wz_phone_done_next))
    }
}

@Composable
private fun Failed(openWindow: () -> Unit, reason: String, onBack: (() -> Unit)?) {
    val kind = PhoneFailureKind.of(reason)
    val (titleRes, bodyRes, topic) = when (kind) {
        PhoneFailureKind.TIMEOUT -> Triple(R.string.wz_phone_fail_timeout_t, R.string.wz_phone_fail_timeout_b, Troubles.phoneTimeout)
        PhoneFailureKind.MISMATCH_OR_REJECTED -> Triple(R.string.wz_phone_fail_mismatch_t, R.string.wz_phone_fail_mismatch_b, Troubles.phoneMismatch)
        PhoneFailureKind.STORAGE -> Triple(R.string.wz_phone_fail_storage_t, R.string.wz_phone_fail_storage_b, Troubles.phoneAgain)
        PhoneFailureKind.OTHER -> Triple(R.string.wz_phone_fail_other_t, R.string.wz_phone_fail_other_b, Troubles.phoneNothing)
    }
    WizardFrame(
        step = STEP, totalSteps = TOTAL, title = stringResource(titleRes), onBack = onBack,
        actions = { BigButton(stringResource(R.string.ui_try_again), openWindow) },
        footer = { TroubleshootSection(Troubles.phoneAll, key = "ts-phone-fail-$reason", highlight = topic) }
    ) {
        RuledCard(rule = IbTheme.colors.danger, container = IbTheme.colors.dangerContainer) {
            Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.onDangerContainer)
        }
        WhatHappensNext(stringResource(R.string.wz_phone_fail_next))
    }
}
