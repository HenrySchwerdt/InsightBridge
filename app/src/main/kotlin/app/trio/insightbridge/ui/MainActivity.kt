/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.BridgeApp
import app.trio.insightbridge.R
import app.trio.insightbridge.ui.theme.InsightBridgeTheme

/**
 * Single Compose activity: setup wizard, main screen and the hidden developer screen.
 *
 * It must never show an empty screen: if the runtime or the Compose content cannot be created, a
 * plain-View fallback with the error and the crash log tail is shown instead.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        try {
            val rt = BridgeApp.runtime(this)
            setContent {
                InsightBridgeTheme {
                    AppRoot(rt)
                }
            }
        } catch (t: Throwable) {
            PumpLog.e("UI could not start", t)
            showFallback(t)
        }
    }

    private fun showFallback(error: Throwable) {
        val tail = try { BridgeApp.runtime(this).crashLog.readTail(3000) } catch (_: Throwable) { "" }
        val text = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            text = getString(R.string.app_name) + "\n\n" + error.javaClass.simpleName + ": " + (error.message ?: "") + "\n\n" + tail
            setPadding(48, 96, 48, 48)
            gravity = Gravity.START
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#7A1F1F"))
                addView(LinearLayout(this@MainActivity).apply { addView(text) })
            }
        )
    }
}
