/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.wizard

import androidx.lifecycle.ViewModel
import app.trio.insightbridge.BridgeRuntime
import app.trio.insightbridge.service.PumpPairingFlow

/** Keeps the pump pairing driver alive across rotation; cancels it when the wizard is left for good. */
class WizardViewModel(rt: BridgeRuntime) : ViewModel() {

    val pumpFlow = PumpPairingFlow(rt)

    init {
        if (app.trio.insightbridge.BuildConfig.DEBUG) rt.debugPumpFlow = pumpFlow
    }

    override fun onCleared() {
        pumpFlow.cancel()
    }
}
