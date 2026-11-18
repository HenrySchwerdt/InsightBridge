/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.wizard

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trio.insightbridge.R
import app.trio.insightbridge.ui.components.LocalOpenDeveloper
import app.trio.insightbridge.ui.components.VersionLabel
import app.trio.insightbridge.ui.components.rememberAnimationsEnabled
import app.trio.insightbridge.ui.theme.IbTheme

/**
 * Wizard screen: back arrow, thin segmented progress with a muted "Step n of N" caption, title,
 * body (hero content), and a fixed bottom area for the actions. With large fonts or in landscape
 * the actions scroll with the content so nothing is ever clipped.
 */
@Composable
fun WizardFrame(
    step: Int,
    totalSteps: Int,
    title: String,
    modifier: Modifier = Modifier,
    subLabel: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
    showVersion: Boolean = false,
    body: @Composable ColumnScope.() -> Unit
) {
    val cfg = LocalConfiguration.current
    val c = IbTheme.colors
    val sticky = cfg.orientation == Configuration.ORIENTATION_PORTRAIT && cfg.screenHeightDp >= 600 && cfg.fontScale <= 1.3f
    val d = if (rememberAnimationsEnabled()) 260 else 0
    Column(modifier.fillMaxSize()) {
        StepHeader(step, totalSteps, onBack)
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = title,
                transitionSpec = {
                    (fadeIn(tween(d, delayMillis = d / 4)) + slideInVertically(tween(d)) { it / 30 }) togetherWith fadeOut(tween(d / 2))
                },
                label = "wizardStep"
            ) { shownTitle ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        shownTitle,
                        style = MaterialTheme.typography.headlineLarge,
                        color = c.foreground,
                        modifier = Modifier.semantics { heading() }
                    )
                    body()
                    if (!sticky) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
                    footer()
                    if (showVersion) VersionLabel(onDeveloper = LocalOpenDeveloper.current)
                }
            }
            if (sticky) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, c.background)))
                )
            }
        }
        if (sticky) {
            Column(
                Modifier.fillMaxWidth().background(c.background).padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) { actions() }
        }
    }
}

@Composable
private fun StepHeader(step: Int, total: Int, onBack: (() -> Unit)?) {
    val c = IbTheme.colors
    val label = stringResource(R.string.ui_step_of, step, total)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_back), tint = c.foreground)
                }
            }
            Box(Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, modifier = Modifier.padding(end = 12.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (i in 1..total) {
                Box(Modifier.weight(1f).height(4.dp).background(if (i <= step) c.foreground else c.muted, RoundedCornerShape(50)))
            }
        }
    }
}
