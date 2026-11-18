/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.aaps.pump.insight.utils

// Object monitor helpers (Kotlin hides wait/notify on Any). Replaces app.aaps.core.utils from AndroidAPS.

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal fun Any.wait() = (this as java.lang.Object).wait()

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal fun Any.waitMillis(millis: Long) = (this as java.lang.Object).wait(millis)

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal fun Any.notifyAll() = (this as java.lang.Object).notifyAll()
