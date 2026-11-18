/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge

import android.app.Application
import android.content.Context
import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.service.Restarter

/** Application: builds the runtime and installs the global crash handler. */
class BridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val runtime = runtime(this)
        installCrashHandler(runtime)
    }

    private fun installCrashHandler(runtime: BridgeRuntime) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                runtime.crashLog.append(
                    "CRASH ${java.time.Instant.now()} thread=${thread.name} ${error.javaClass.name}: ${error.message?.take(200)}\n" +
                        error.stackTrace.take(25).joinToString("\n") { "    at $it" }
                )
                runtime.settings.crashPending = true
                // Schedule the restart BEFORE the process dies.
                if (runtime.settings.setupDone) Restarter.schedule(this, 2_000)
            } catch (_: Throwable) {
            }
            if (previous != null) previous.uncaughtException(thread, error) else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
        PumpLog.i("Process started")
    }

    companion object {

        @Volatile private var instance: BridgeRuntime? = null

        fun runtime(context: Context): BridgeRuntime =
            instance ?: synchronized(this) {
                instance ?: BridgeRuntime(context.applicationContext as Application).also { instance = it }
            }
    }
}
