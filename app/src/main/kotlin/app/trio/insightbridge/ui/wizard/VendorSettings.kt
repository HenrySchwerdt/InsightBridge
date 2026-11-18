/*
 * This file is part of InsightBridge (Copyright (C) InsightBridge contributors).
 * Licensed under the GNU Affero General Public License v3 or later, see the LICENSE file.
 */
package app.trio.insightbridge.ui.wizard

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.trio.insightbridge.ui.util.Vendor

/**
 * Best-effort deep links to vendor specific "autostart / background" screens. These component
 * names come from public community lists and could NOT be verified on real devices; every failure
 * falls back to the standard app-details screen.
 */
object VendorSettings {

    enum class Opened { VENDOR_SCREEN, APP_DETAILS, NOTHING }

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    fun candidates(v: Vendor): List<Intent> = when (v) {
        Vendor.SAMSUNG -> listOf(
            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            component("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )
        Vendor.XIAOMI -> listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        Vendor.HUAWEI -> listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        )
        Vendor.OPPO -> listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        )
        Vendor.VIVO -> listOf(
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        )
        Vendor.ASUS -> listOf(
            component("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")
        )
        Vendor.OTHER -> emptyList()
    }

    fun detect(): Vendor = Vendor.detect(Build.MANUFACTURER, Build.BRAND)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** Opens the vendor screen if one resolves, else the app details screen. */
    fun open(context: Context, v: Vendor = detect()): Opened {
        for (intent in candidates(v)) {
            if (tryStart(context, intent)) return Opened.VENDOR_SCREEN
        }
        return if (tryStart(context, appDetailsIntent(context))) Opened.APP_DETAILS else Opened.NOTHING
    }

    fun openAppDetails(context: Context): Boolean = tryStart(context, appDetailsIntent(context))

    /** On Android 11+ other apps' components may be invisible to resolveActivity, so the start itself decides. */
    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }
}
