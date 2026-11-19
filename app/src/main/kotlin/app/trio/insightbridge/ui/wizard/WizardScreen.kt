/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.trio.insightbridge.BridgeRuntime
import app.trio.insightbridge.service.BridgeService
import app.trio.insightbridge.service.Perms
import app.trio.insightbridge.service.Watchdog

/** Which part of the wizard is shown: all four steps, or just one re-entered from the main screen. */
object WizardMode {

    const val FULL = 0
    const val PUMP_ONLY = 1
    const val PHONE_ONLY = 2
}

private const val S_PERMS = 1
private const val S_PUMP = 2
private const val S_PHONE = 3
private const val S_DONE = 4

/** Linear four-step setup wizard with simple state-based navigation. */
@Composable
fun WizardScreen(
    rt: BridgeRuntime,
    mode: Int,
    simulated: Boolean,
    onExit: () -> Unit,
    onOpenMain: () -> Unit,
    onOpenDeveloper: () -> Unit
) {
    val ctx = LocalContext.current
    val vm: WizardViewModel = viewModel(factory = viewModelFactory { initializer { WizardViewModel(rt) } })
    val health by rt.health.collectAsStateWithLifecycle()
    val status by rt.status.collectAsStateWithLifecycle()
    val phoneState by rt.pairing.state.collectAsStateWithLifecycle()

    var step by rememberSaveable {
        mutableIntStateOf(
            when (mode) {
                WizardMode.PUMP_ONLY -> S_PUMP
                WizardMode.PHONE_ONLY -> S_PHONE
                else -> when {
                    !Perms.bluetoothGranted(ctx) -> S_PERMS
                    vm.pumpFlow.state.value !is app.trio.insightbridge.service.PumpPairState.Idle -> S_PUMP
                    rt.pairing.state.value !is app.trio.insightbridge.engine.PairingUiState.Idle -> S_PHONE
                    !(rt.pumpPaired || rt.settings.simulatorEnabled) -> S_PUMP
                    !rt.phonePaired -> S_PHONE
                    else -> S_DONE
                }
            }
        )
    }
    var permSub by rememberSaveable { mutableIntStateOf(0) }
    var pumpSub by rememberSaveable {
        mutableIntStateOf(if (vm.pumpFlow.state.value !is app.trio.insightbridge.service.PumpPairState.Idle) PUMP_SUB_SCAN else PUMP_SUB_REMOVE)
    }
    var pairAgain by rememberSaveable { mutableStateOf(mode == WizardMode.PUMP_ONLY) }

    val atVeryFirst = mode == WizardMode.FULL && step == S_PERMS && permSub == 0
    val goBack: () -> Unit = {
        when (step) {
            S_PERMS -> if (permSub > 0) permSub-- else onExit()
            S_PUMP -> when {
                pumpSub > PUMP_SUB_REMOVE -> pumpSub--
                mode == WizardMode.PUMP_ONLY -> onExit()
                else -> { step = S_PERMS; permSub = 2 }
            }
            S_PHONE -> if (mode == WizardMode.PHONE_ONLY) onExit() else step = S_PUMP
            else -> step = S_PHONE
        }
    }
    BackHandler(enabled = !atVeryFirst) { goBack() }
    val back: (() -> Unit)? = if (atVeryFirst) null else goBack

    when (step) {
        S_PERMS -> when (permSub) {
            0 -> PermissionsScreen(onNext = { permSub = 1 }, onBack = back)
            1 -> BatteryScreen(onNext = { permSub = 2 }, onBack = goBack)
            else -> VendorScreen(onNext = { step = S_PUMP; permSub = 0 }, onBack = goBack)
        }
        S_PUMP -> PumpStep(
            PumpStepArgs(
                flow = vm.pumpFlow,
                sub = pumpSub,
                onSub = { pumpSub = it },
                onBack = goBack,
                onFinished = { if (mode == WizardMode.PUMP_ONLY) onExit() else { step = S_PHONE } },
                simulated = simulated,
                pumpPaired = health.pumpPaired || rt.pumpPaired,
                pairAgain = pairAgain,
                onPairAgain = { pairAgain = true },
                onOpenDeveloper = onOpenDeveloper
            )
        )
        S_PHONE -> PhoneStep(
            pairing = rt.pairing,
            phonePaired = health.phonePaired || rt.phonePaired || phoneState is app.trio.insightbridge.engine.PairingUiState.Done,
            onBack = goBack,
            resetStale = mode == WizardMode.PHONE_ONLY,
            bridgeVisible = health.advertising || !health.serviceRunning,
            onFinished = { if (mode == WizardMode.PHONE_ONLY) onExit() else step = S_DONE }
        )
        else -> DoneStep(
            health = health,
            status = status,
            onBack = goBack,
            onOpenMain = {
                rt.settings.setupDone = true
                Watchdog.schedule(ctx)
                BridgeService.start(ctx)
                onOpenMain()
            }
        )
    }
}
