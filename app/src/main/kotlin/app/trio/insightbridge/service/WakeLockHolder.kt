/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.service

import android.content.Context
import android.os.PowerManager
import app.aaps.pump.insight.utils.PumpLog

/**
 * Short partial wake locks around BLE traffic. With the screen off the CPU may fall asleep between Bluetooth
 * callbacks; work started by a callback (decrypting, talking to the pump over RFCOMM, answering the iPhone) must
 * finish first. The lock is never held longer than needed: [touch] holds for a few seconds after a BLE event,
 * [requestStarted]/[requestFinished] hold while requests are being served (with a hard timeout as a safety net).
 */
class WakeLockHolder(context: Context, private val clock: () -> Long = System::currentTimeMillis) {

    companion object {

        const val EVENT_HOLD_MS = 5_000L
        const val REQUEST_HOLD_MS = 60_000L
    }

    private val lock: PowerManager.WakeLock? = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InsightBridge:ble").apply { setReferenceCounted(false) }
    } catch (t: Throwable) {
        PumpLog.w("Could not create wake lock", t)
        null
    }
    private var heldUntil = 0L
    private var inFlight = 0

    /** Keeps the CPU awake for [ms] from now (never shortens an existing hold). */
    @Synchronized
    fun touch(ms: Long = EVENT_HOLD_MS) = extend(ms)

    @Synchronized
    fun requestStarted() {
        inFlight++
        extend(REQUEST_HOLD_MS)
    }

    @Synchronized
    fun requestFinished() {
        if (inFlight > 0) inFlight--
        if (inFlight == 0) {
            val now = clock()
            // Drop the long request hold, keep a short tail for the response notification and the next event.
            if (heldUntil - now > EVENT_HOLD_MS) {
                try { lock?.release() } catch (_: Throwable) {}
                heldUntil = 0
                extend(EVENT_HOLD_MS)
            }
        }
    }

    @Synchronized
    fun releaseAll() {
        inFlight = 0
        heldUntil = 0
        try { if (lock?.isHeld == true) lock.release() } catch (_: Throwable) {}
    }

    private fun extend(ms: Long) {
        val now = clock()
        val until = now + ms
        if (until <= heldUntil) return
        try {
            lock?.acquire(until - now)
            heldUntil = until
        } catch (t: Throwable) {
            PumpLog.w("wake lock acquire failed", t)
        }
    }
}
