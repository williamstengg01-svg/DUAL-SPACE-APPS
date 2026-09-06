package com.dualspace.clone.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import com.dualspace.clone.model.Clone
import com.dualspace.clone.model.InstalledApp
import com.dualspace.clone.util.StorageUtil
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackbox.utils.AbiUtils
import java.io.File

/** Thin façade over the engine + CloneStore. All methods are blocking; call from IO. */
object CloneManager {

    sealed class Result {
        data class Ok(val clone: Clone) : Result()
        data class Error(val message: String) : Result()
    }

    private val core get() = BlackBoxCore.get()

    // ---------------------------------------------------------------- host app list

    fun installedApps(context: Context, includeSystem: Boolean): List<InstalledApp> {
        val pm = context.packageManager
        val self = context.packageName
        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != self && !it.packageName.startsWith("com.dualspace.clone") }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map {
                InstalledApp(
                    packageName = it.packageName,
                    label = it.loadLabel(pm).toString(),
                    sourceDir = it.sourceDir,
                    abiSupported = runCatching { AbiUtils.isSupport(File(it.sourceDir)) }.getOrDefault(true),
                    isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Create a brand-new clone of [packageName]:
     *  1. pick the next free virtual user slot for this package (unlimited),
     *  2. make sure Google Play Services is already linked in that slot,
     *  3. install the app into the slot.
     */
    fun createClone(context: Context, packageName: String): Result {
        val pm = context.packageManager
        val appLabel = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            return Result.Error("App not installed: $packageName")
        }

        val userId = CloneStore.nextUserIdFor(packageName)
        ensureUser(userId)

        // Step 2: pre-link GMS *before* the app is installed so first launch already has it.
        GmsLinker.ensure(userId)

        // Step 3: mirror the installed APK into the sandbox.
        val install = core.installPackageAsUser(packageName, userId)
        if (!install.success) {
            return Result.Error(install.msg ?: "Install failed")
        }

        val index = CloneStore.nextIndexFor(packageName)
        val clone = Clone(
            packageName = packageName,
            userId = userId,
            index = index,
            label = if (index == 1) appLabel else "$appLabel $index"
        )
        CloneStore.add(clone)
        return Result.Ok(clone)
    }

    fun launch(clone: Clone): Boolean {
        if (clone.frozen) return false
        // Belt and braces: GMS could have been wiped by "clear data"; make sure it is there.
        GmsLinker.ensure(clone.userId)
        return runCatching { core.launchApk(clone.packageName, clone.userId) }.getOrDefault(false)
    }

    fun stop(clone: Clone) {
        runCatching { core.stopPackage(clone.packageName, clone.userId) }
    }

    fun setFrozen(clone: Clone, frozen: Boolean) {
        if (frozen) stop(clone)
        clone.frozen = frozen
        CloneStore.update(clone)
    }

    fun clearData(clone: Clone) {
        stop(clone)
        runCatching { core.clearPackage(clone.packageName, clone.userId) }
    }

    fun delete(clone: Clone) {
        stop(clone)
        runCatching { core.uninstallPackageAsUser(clone.packageName, clone.userId) }
        clone.customIconPath?.let { File(it).delete() }
        CloneStore.remove(clone.id)
        // Release the slot if it is now empty (GMS mirror aside).
        val stillUsed = CloneStore.all().any { it.userId == clone.userId }
        if (!stillUsed) {
            runCatching {
                if (core.isInstallGms(clone.userId)) core.uninstallGms(clone.userId)
                core.deleteUser(clone.userId)
            }
        }
    }

    private fun ensureUser(userId: Int) {
        val exists = runCatching { core.users.any { it.id == userId } }.getOrDefault(false)
        if (!exists) runCatching { core.createUser(userId) }
    }

    // ---------------------------------------------------------------- storage

    /** Bytes used by this clone: internal data + external data + cache. */
    fun storageBytes(clone: Clone): Long {
        val dirs = listOf(
            BEnvironment.getDataDir(clone.packageName, clone.userId),
            BEnvironment.getDeDataDir(clone.packageName, clone.userId),
            BEnvironment.getExternalDataDir(clone.packageName, clone.userId)
        )
        return dirs.sumOf { StorageUtil.dirSize(it) }
    }

    fun totalStorageBytes(): Long = CloneStore.all().sumOf { storageBytes(it) }

    // ---------------------------------------------------------------- icons

    fun icon(context: Context, clone: Clone): Drawable {
        clone.customIconPath?.let { path ->
            BitmapFactory.decodeFile(path)?.let { return BitmapDrawable(context.resources, it) }
        }
        val pm = context.packageManager
        val base = runCatching { pm.getApplicationIcon(clone.packageName) }
            .getOrElse { pm.defaultActivityIcon }
        return BitmapDrawable(context.resources, badge(base.toBitmap(192, 192), clone.index))
    }

    /** Draw a small numbered badge in the corner so clones of the same app are distinguishable. */
    private fun badge(src: Bitmap, index: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val r = out.width * 0.18f
        val cx = out.width - r - 4f
        val cy = out.height - r - 4f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E88E5") }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.drawCircle(cx, cy, r + 3f, ring)
        c.drawCircle(cx, cy, r, bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = r * 1.25f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val ty = cy - (text.descent() + text.ascent()) / 2
        c.drawText(index.toString(), cx, ty, text)
        return out
    }
}
