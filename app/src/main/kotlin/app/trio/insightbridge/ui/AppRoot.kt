/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trio.insightbridge.BridgeRuntime
import app.trio.insightbridge.backend.BackendKind
import app.trio.insightbridge.service.BridgeService
import app.trio.insightbridge.service.Perms
import app.trio.insightbridge.ui.components.IbRoot
import app.trio.insightbridge.ui.components.LocalOpenDeveloper
import app.trio.insightbridge.ui.dev.DevScreen
import app.trio.insightbridge.ui.main.MainScreen
import app.trio.insightbridge.ui.wizard.WizardMode
import app.trio.insightbridge.ui.wizard.WizardScreen

private const val SCREEN_WIZARD = 0
private const val SCREEN_MAIN = 1
private const val SCREEN_DEV = 2

/** Single-activity navigation: wizard, main, developer. State survives rotation via rememberSaveable. */
@Composable
fun AppRoot(rt: BridgeRuntime) {
    val ctx = LocalContext.current
    var screen by rememberSaveable { mutableIntStateOf(if (rt.settings.setupDone) SCREEN_MAIN else SCREEN_WIZARD) }
    var returnTo by rememberSaveable { mutableIntStateOf(SCREEN_MAIN) }
    var wizardMode by rememberSaveable { mutableIntStateOf(WizardMode.FULL) }
    val health by rt.health.collectAsStateWithLifecycle()
    val backend by rt.backend.collectAsStateWithLifecycle()
    val simulated = health.simulated || backend?.kind == BackendKind.SIMULATED || rt.settings.simulatorEnabled

    // After a process restart the service may be gone: bring it back as soon as the UI is visible.
    LaunchedEffect(Unit) {
        if (Perms.bluetoothGranted(ctx)) BridgeService.start(ctx)
    }

    CompositionLocalProvider(
        LocalOpenDeveloper provides { if (screen != SCREEN_DEV) { returnTo = screen; screen = SCREEN_DEV } }
    ) {
        IbRoot(simulated = simulated) {
            when (screen) {
                SCREEN_DEV -> DevScreen(rt, onBack = { screen = returnTo })
                SCREEN_WIZARD -> WizardScreen(
                    rt = rt,
                    mode = wizardMode,
                    simulated = simulated,
                    onExit = { wizardMode = WizardMode.FULL; screen = if (rt.settings.setupDone) SCREEN_MAIN else SCREEN_WIZARD },
                    onOpenMain = { wizardMode = WizardMode.FULL; screen = SCREEN_MAIN },
                    onOpenDeveloper = { returnTo = SCREEN_WIZARD; screen = SCREEN_DEV }
                )
                else -> MainScreen(
                    rt = rt,
                    onRepairPump = { wizardMode = WizardMode.PUMP_ONLY; screen = SCREEN_WIZARD },
                    onRepairPhone = { wizardMode = WizardMode.PHONE_ONLY; screen = SCREEN_WIZARD }
                )
            }
        }
    }
}
