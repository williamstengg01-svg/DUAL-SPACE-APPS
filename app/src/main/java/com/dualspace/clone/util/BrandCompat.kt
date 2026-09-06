package com.dualspace.clone.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import com.dualspace.clone.R

/**
 * OEM-specific "keep me alive" settings. Chinese-brand skins (MIUI/HyperOS, Funtouch/OriginOS,
 * ColorOS) and One UI all kill background processes aggressively unless the user
 * whitelists the app. Each step deep-links to the exact page; if a component is missing
 * on this firmware version we fall back to the next candidate and finally to the app's
 * own system settings page.
 */
object BrandCompat {

    enum class Brand(@StringRes val label: Int) {
        SAMSUNG(R.string.brand_samsung),
        XIAOMI(R.string.brand_xiaomi),
        VIVO(R.string.brand_vivo),
        OPPO(R.string.brand_oppo),
        OTHER(R.string.brand_other)
    }

    data class Step(
        val key: String,
        @StringRes val title: Int,
        @StringRes val description: Int,
        val candidates: List<Intent>,
        val required: Boolean = true
    )

    fun detect(): Brand {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            "samsung" in m -> Brand.SAMSUNG
            "xiaomi" in m || "redmi" in m || "poco" in m -> Brand.XIAOMI
            "vivo" in m || "iqoo" in m -> Brand.VIVO
            "oppo" in m || "realme" in m || "oneplus" in m -> Brand.OPPO
            else -> Brand.OTHER
        }
    }

    fun steps(context: Context): List<Step> {
        val pkg = context.packageName
        val brand = detect()
        val list = mutableListOf<Step>()

        // 1. Battery optimisation exemption — every brand.
        list += Step(
            key = "battery",
            title = R.string.step_battery_title,
            description = R.string.step_battery_desc,
            candidates = listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        )

        // 2. Autostart / background start permission — OEM specific.
        when (brand) {
            Brand.XIAOMI -> list += Step(
                "autostart", R.string.step_autostart_title, R.string.step_autostart_desc_xiaomi,
                listOf(
                    component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartDetailManagementActivity")
                        .putExtra("pkg_name", pkg).putExtra("pkg_label", appLabel(context))
                )
            )
            Brand.VIVO -> list += Step(
                "autostart", R.string.step_autostart_title, R.string.step_autostart_desc_vivo,
                listOf(
                    component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                    component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                )
            )
            Brand.OPPO -> list += Step(
                "autostart", R.string.step_autostart_title, R.string.step_autostart_desc_oppo,
                listOf(
                    component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                    component("com.coloros.oppoguardelf", "com.coloros.oppoguardelf.MonitoredPkgActivity"),
                    component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
                )
            )
            Brand.SAMSUNG -> list += Step(
                "sleeping", R.string.step_sleeping_title, R.string.step_sleeping_desc_samsung,
                listOf(
                    component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
                )
            )
            Brand.OTHER -> Unit
        }

        // 3. Background pop-up / display-over-other-apps — needed so a clone can open an
        //    Activity when the user taps its notification (MIUI and Vivo gate this separately).
        when (brand) {
            Brand.XIAOMI -> list += Step(
                "popup", R.string.step_popup_title, R.string.step_popup_desc_xiaomi,
                listOf(
                    Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        .putExtra("extra_pkgname", pkg),
                    Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                        .putExtra("extra_pkgname", pkg)
                )
            )
            Brand.VIVO -> list += Step(
                "popup", R.string.step_popup_title, R.string.step_popup_desc_vivo,
                listOf(
                    component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")
                        .putExtra("packagename", pkg)
                )
            )
            else -> Unit
        }

        // 4. Extra battery page on Xiaomi / Oppo ("No restrictions").
        when (brand) {
            Brand.XIAOMI -> list += Step(
                "power", R.string.step_power_title, R.string.step_power_desc_xiaomi,
                listOf(
                    component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                        .putExtra("package_name", pkg).putExtra("package_label", appLabel(context))
                ),
                required = false
            )
            Brand.VIVO -> list += Step(
                "power", R.string.step_power_title, R.string.step_power_desc_vivo,
                listOf(
                    component("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
                ),
                required = false
            )
            Brand.OPPO -> list += Step(
                "power", R.string.step_power_title, R.string.step_power_desc_oppo,
                listOf(
                    component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
                    component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity")
                ),
                required = false
            )
            else -> Unit
        }

        // 5. Notification access — every brand.
        list += Step(
            "notifications", R.string.step_notif_title, R.string.step_notif_desc,
            listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            ),
            required = false
        )

        return list
    }

    /** Launch the first resolvable candidate; fall back to the app info page. */
    fun open(context: Context, step: Step): Boolean {
        val pm = context.packageManager
        for (intent in step.candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) != null) {
                runCatching { context.startActivity(intent); return true }
            }
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(fallback); true }.getOrDefault(false)
    }

    @SuppressLint("BatteryLife")
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    private fun appLabel(context: Context) = context.applicationInfo.loadLabel(context.packageManager).toString()
}
