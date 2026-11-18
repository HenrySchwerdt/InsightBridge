/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.dev

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.trio.insightbridge.BridgeRuntime
import app.trio.insightbridge.R
import app.trio.insightbridge.backend.SimulatedPump
import app.trio.insightbridge.protocol.ErrorCode
import app.trio.insightbridge.ui.components.BigButton
import app.trio.insightbridge.ui.components.ButtonStyle
import app.trio.insightbridge.ui.components.IbConfirmDialog
import app.trio.insightbridge.ui.components.RuledCard
import app.trio.insightbridge.ui.components.SectionHeading
import app.trio.insightbridge.ui.theme.IbTheme

private const val LINK_DROP_MS = 30_000L

/** Hidden developer screen (version label tapped seven times). */
@Composable
fun DevScreen(rt: BridgeRuntime, onBack: () -> Unit) {
    BackHandler { onBack() }
    val health by rt.health.collectAsStateWithLifecycle()
    val backend by rt.backend.collectAsStateWithLifecycle()
    var simOn by remember { mutableStateOf(rt.settings.simulatorEnabled) }
    var confirmEnable by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<Int?>(null) }
    val pumpPaired = health.pumpPaired || rt.pumpPaired

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        app.trio.insightbridge.ui.components.BackArrow(onBack, Modifier.offset(x = (-12).dp))
        Text(stringResource(R.string.dev_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.dev_warning), style = MaterialTheme.typography.bodyLarge, color = IbTheme.colors.mutedForeground)

        RuledCard {
            SectionHeading(stringResource(R.string.dev_sim_title))
            Text(stringResource(R.string.dev_sim_explain), style = MaterialTheme.typography.bodySmall, color = IbTheme.colors.mutedForeground)
            val canEnable = !pumpPaired || simOn
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = simOn, enabled = canEnable, role = Role.Switch) { want ->
                        if (want) confirmEnable = true else { rt.disableSimulator(); simOn = false }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.dev_sim_switch), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                app.trio.insightbridge.ui.components.IbSwitch(simOn, canEnable)
            }
            if (pumpPaired && !simOn) {
                Text(stringResource(R.string.dev_sim_disabled_paired), style = MaterialTheme.typography.titleSmall, color = IbTheme.colors.attention)
            }
            if (simOn) Text(stringResource(R.string.dev_sim_blocks_pairing), style = MaterialTheme.typography.bodyLarge)
            if (refused) Text(stringResource(R.string.dev_sim_refused), style = MaterialTheme.typography.titleSmall, color = IbTheme.colors.danger)
        }

        if (app.trio.insightbridge.BuildConfig.DEBUG) {
            RuledCard {
                SectionHeading("Debug UI previews (debug build only)")
                Text("Shows wizard screens with fake codes. Nothing is paired.", style = MaterialTheme.typography.bodyLarge)
                BigButton("Preview: iPhone code 482 913", {
                    rt.pairing.open(); rt.pairing.beginAttempt(); rt.pairing.showCode("482913")
                }, style = ButtonStyle.Secondary)
                BigButton("Preview: iPhone paired (done screens)", { rt.pairing.succeeded() }, style = ButtonStyle.Secondary)
                BigButton("Preview: pump code compare", { rt.debugPumpFlow?.debugShowCode("K7M 2QX 9PR A") }, style = ButtonStyle.Secondary)
            }
        }

        val sim = backend as? SimulatedPump
        if (simOn) {
            if (sim == null) {
                Text(stringResource(R.string.dev_sim_starting), style = MaterialTheme.typography.bodyLarge)
            } else {
                Injections(sim) { last = it }
            }
        }
        last?.let {
            Text(
                stringResource(R.string.dev_injected, stringResource(it)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }

    if (confirmEnable) {
        IbConfirmDialog(
            title = stringResource(R.string.dev_sim_confirm_title),
            text = stringResource(R.string.dev_sim_confirm_body),
            confirmLabel = stringResource(R.string.dev_sim_confirm_yes),
            destructive = true,
            onConfirm = {
                confirmEnable = false
                val ok = rt.enableSimulator()
                simOn = ok
                refused = !ok
            },
            onDismiss = { confirmEnable = false }
        )
    }
}

@Composable
private fun Injections(sim: SimulatedPump, onDone: (Int) -> Unit) {
    fun act(label: Int, block: () -> Unit) {
        block()
        onDone(label)
    }
    RuledCard {
        SectionHeading(stringResource(R.string.dev_inj_link))
        BigButton(stringResource(R.string.dev_inj_drop_link), { act(R.string.dev_inj_drop_link) { sim.injectLinkDrop(LINK_DROP_MS) } }, style = ButtonStyle.Secondary)
        BigButton(stringResource(R.string.dev_inj_restore_link), { act(R.string.dev_inj_restore_link) { sim.restoreLink() } }, style = ButtonStyle.Secondary)
    }
    RuledCard {
        SectionHeading(stringResource(R.string.dev_inj_cmd))
        BigButton(stringResource(R.string.dev_inj_uncertain_executed), { act(R.string.dev_inj_uncertain_executed) { sim.injectUncertain(null, executed = true) } }, style = ButtonStyle.Secondary)
        BigButton(stringResource(R.string.dev_inj_uncertain_not), { act(R.string.dev_inj_uncertain_not) { sim.injectUncertain(null, executed = false) } }, style = ButtonStyle.Secondary)
        BigButton(stringResource(R.string.dev_inj_busy), { act(R.string.dev_inj_busy) { sim.injectFailure(null, ErrorCode.PUMP_BUSY) } }, style = ButtonStyle.Secondary)
    }
    RuledCard {
        SectionHeading(stringResource(R.string.dev_inj_alerts))
        BigButton(stringResource(R.string.dev_inj_occlusion), { act(R.string.dev_inj_occlusion) { sim.injectAlert("MAINTENANCE_24") } }, style = ButtonStyle.Secondary)
        BigButton(stringResource(R.string.dev_inj_cartridge), { act(R.string.dev_inj_cartridge) { sim.injectAlert("WARNING_31", remainingInsulin = 20.0) } }, style = ButtonStyle.Secondary)
        BigButton(stringResource(R.string.dev_inj_battery_low), { act(R.string.dev_inj_battery_low) { sim.injectAlert("WARNING_32") } }, style = ButtonStyle.Secondary)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    RuledCard {
        SectionHeading("Pump activity (simulator)")
        BigButton("Start TBR 80 % for 30 min", { scope.launch { runCatching { sim.setTbr(80, 30) }; onDone(R.string.dev_inj_levels) } }, style = ButtonStyle.Secondary)
        BigButton("Deliver bolus 1.5 U", { scope.launch { runCatching { sim.deliverBolus(java.math.BigDecimal("1.5")) }; onDone(R.string.dev_inj_levels) } }, style = ButtonStyle.Secondary)
    }
    RuledCard {
        SectionHeading(stringResource(R.string.dev_inj_levels))
        listOf(100, 50, 10).forEach { pct ->
            BigButton(stringResource(R.string.dev_inj_set_battery, pct), { act(R.string.dev_inj_levels) { sim.setBattery(pct) } }, style = ButtonStyle.Secondary)
        }
        listOf(200, 50, 10, 0).forEach { u ->
            BigButton(stringResource(R.string.dev_inj_set_reservoir, u), { act(R.string.dev_inj_levels) { sim.setReservoir(u.toDouble()) } }, style = ButtonStyle.Secondary)
        }
    }
}
