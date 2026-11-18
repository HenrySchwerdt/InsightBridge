/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

import android.content.Context
import android.os.Process
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.aaps.pump.insight.utils.PumpLog
import app.trio.insightbridge.BridgeApp
import java.util.concurrent.TimeUnit

/** Schedules the 15 minute watchdog. */
object Watchdog {

    private const val NAME = "insightbridge-watchdog"

    fun schedule(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (t: Throwable) {
            PumpLog.e("Could not schedule watchdog", t)
        }
    }
}

/** Restarts the foreground service if it is not running or has stopped making progress. */
class WatchdogWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val runtime = BridgeApp.runtime(context)
            val decision = WatchdogLogic.decide(
                WatchdogLogic.Input(
                    now = System.currentTimeMillis(),
                    shouldRun = runtime.settings.setupDone,
                    serviceRunning = BridgeService.running,
                    lastHeartbeat = BridgeService.lastHeartbeat,
                    bluetoothPermissionsGranted = Perms.bluetoothGranted(context)
                )
            )
            PumpLog.i("Watchdog: $decision")
            when (decision) {
                WatchdogLogic.Decision.NOTHING -> Unit
                WatchdogLogic.Decision.START_SERVICE -> BridgeService.start(context)
                WatchdogLogic.Decision.RESTART_SERVICE -> {
                    BridgeService.stop(context)
                    BridgeService.start(context)
                }
                WatchdogLogic.Decision.RESTART_PROCESS -> {
                    runtime.crashLog.append("watchdog: service heartbeat stalled, restarting process")
                    runtime.settings.crashPending = true
                    Restarter.schedule(context, 3_000)
                    Process.killProcess(Process.myPid())
                }
                WatchdogLogic.Decision.NOTIFY_PERMISSIONS_MISSING -> runtime.notifications.permissionsMissing()
            }
        } catch (t: Throwable) {
            PumpLog.e("Watchdog failed", t)
        }
        return Result.success()
    }
}
