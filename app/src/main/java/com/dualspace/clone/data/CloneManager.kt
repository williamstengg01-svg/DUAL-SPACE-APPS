package com.dualspace.clone.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import com.dualspace.clone.DualSpaceApp
import com.dualspace.clone.model.Clone
import com.dualspace.clone.model.InstalledApp
import com.dualspace.clone.util.CrashLog
import com.dualspace.clone.util.DsLog
import com.dualspace.clone.util.FailureText
import com.dualspace.clone.util.StorageUtil
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.env.BEnvironment
import top.niunaijun.blackbox.utils.AbiUtils
import java.io.File

/**
 * Thin façade over the engine + CloneStore. All methods are blocking and talk to the engine
 * over Binder; call them from `Dispatchers.IO`. None of them throws: every engine failure
 * becomes a result value, is written to the log file and, where useful, to Diagnostics.
 */
object CloneManager {
    private const val TAG = "CloneManager"

    sealed class Result {
        data class Ok(val clone: Clone) : Result()
        data class Error(val message: String) : Result()
    }

    sealed class LaunchResult {
        object Ok : LaunchResult()
        data class Failed(val reason: String) : LaunchResult()
    }

    /** Phases of [createClone], reported to the progress dialog. */
    enum class Step(val percent: Int) {
        PREPARING(8), LINKING_GMS(30), INSTALLING(65), FINISHING(92), DONE(100)
    }

    private val core get() = BlackBoxCore.get()

    // ---------------------------------------------------------------- host app list

    /**
     * Every launchable app on the phone. One PackageManager query for the launcher activities
     * instead of one Binder call per package, and no APK scanning here — the CPU-architecture
     * check happens when an app is picked ([isAbiSupported]) so the list appears instantly.
     */
    fun installedApps(context: Context, includeSystem: Boolean): List<InstalledApp> {
        val pm = context.packageManager
        val self = context.packageName
        val t0 = SystemClock.elapsedRealtime()
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.toHashSet()
        }.getOrElse { HashSet() }
        val list = pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != self && !it.packageName.startsWith("com.dualspace.clone") }
            .filter { it.packageName in launchable }
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map {
                InstalledApp(
                    packageName = it.packageName,
                    label = runCatching { it.loadLabel(pm).toString() }.getOrDefault(it.packageName),
                    sourceDir = it.sourceDir,
                    isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
        DsLog.d(TAG, "listed ${list.size} launchable apps in ${SystemClock.elapsedRealtime() - t0} ms")
        return list
    }

    /** Opens the APK and checks its native libraries against this build's ABI. IO thread. */
    fun isAbiSupported(app: InstalledApp): Boolean =
        runCatching { AbiUtils.isSupport(File(app.sourceDir)) }.getOrDefault(true)

    // ---------------------------------------------------------------- lifecycle

    /**
     * Create a brand-new clone of [packageName]:
     *  1. pick the next free virtual user slot for this package (unlimited),
     *  2. make sure Google Play Services is already linked in that slot,
     *  3. install the app into the slot,
     *  4. register it.
     * [onStep] is invoked (on the calling thread) as each phase starts.
     */
    fun createClone(context: Context, packageName: String, onStep: (Step) -> Unit = {}): Result {
        val t0 = SystemClock.elapsedRealtime()
        DsLog.i(TAG, "createClone($packageName) start")
        return try {
            val r = createCloneInternal(context, packageName, onStep)
            when (r) {
                is Result.Ok -> DsLog.i(TAG, "createClone($packageName) ok -> slot ${r.clone.userId} '${r.clone.label}' in ${SystemClock.elapsedRealtime() - t0} ms")
                is Result.Error -> DsLog.w(TAG, "createClone($packageName) failed after ${SystemClock.elapsedRealtime() - t0} ms: ${r.message}")
            }
            r
        } catch (t: Throwable) {
            // Anything the engine throws (it is not shy about NullPointerExceptions when its
            // service process is not up yet) must become a message, never a crash of the host.
            CrashLog.note(context, "clone $packageName", t)
            Result.Error(FailureText.describe(t))
        }
    }

    private fun createCloneInternal(context: Context, packageName: String, onStep: (Step) -> Unit): Result {
        onStep(Step.PREPARING)
        val pm = context.packageManager
        val appLabel = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            return Result.Error("App not installed: $packageName")
        }

        val userId = CloneStore.nextUserIdFor(packageName)
        ensureUser(userId)

        // Step 2: pre-link GMS *before* the app is installed so first launch already has it.
        onStep(Step.LINKING_GMS)
        val gmsOk = GmsLinker.ensure(userId)
        if (!gmsOk) DsLog.w(TAG, "GMS could not be linked into slot $userId; continuing without it")

        // Step 3: mirror the installed APK into the sandbox.
        onStep(Step.INSTALLING)
        val t1 = SystemClock.elapsedRealtime()
        val install = core.installPackageAsUser(packageName, userId)
        DsLog.i(TAG, "installPackageAsUser($packageName, user=$userId) -> success=${install?.success} in ${SystemClock.elapsedRealtime() - t1} ms ${install?.msg.orEmpty()}")
        if (install == null || !install.success) {
            return Result.Error(install?.msg ?: "Install failed: the engine returned no result")
        }

        onStep(Step.FINISHING)
        val index = CloneStore.nextIndexFor(packageName)
        val clone = Clone(
            packageName = packageName,
            userId = userId,
            index = index,
            label = if (index == 1) appLabel else "$appLabel $index"
        )
        CloneStore.add(clone)
        // Refresh the indicator for the new slot without blocking the caller for long.
        runCatching { GmsLinker.refreshStatus() }
        onStep(Step.DONE)
        return Result.Ok(clone)
    }

    /**
     * Start a clone. Never throws: every failure comes back as [LaunchResult.Failed] with a
     * human-readable reason, and is also written to the Diagnostics log.
     */
    fun launch(clone: Clone): LaunchResult {
        if (clone.frozen) return LaunchResult.Failed("This clone is frozen.")
        val t0 = SystemClock.elapsedRealtime()
        DsLog.i(TAG, "launch ${clone.packageName} slot ${clone.userId}")
        return try {
            // Belt and braces: GMS could have been wiped by "clear data"; make sure it is there.
            runCatching { GmsLinker.ensure(clone.userId) }

            // The sandbox can lose the package (engine data cleared, interrupted update) while
            // the registry still lists the clone. Re-mirror it instead of failing with
            // "no launch intent".
            val installed = runCatching { core.isInstalled(clone.packageName, clone.userId) }.getOrDefault(true)
            if (!installed) {
                DsLog.w(TAG, "${clone.packageName} missing from slot ${clone.userId}; re-installing")
                ensureUser(clone.userId)
                val r = core.installPackageAsUser(clone.packageName, clone.userId)
                if (r == null || !r.success) {
                    return LaunchResult.Failed("Could not re-install the app into its sandbox: ${r?.msg ?: "no result"}")
                }
            }

            val ok = core.launchApk(clone.packageName, clone.userId)
            DsLog.i(TAG, "launchApk(${clone.packageName}, ${clone.userId}) -> $ok in ${SystemClock.elapsedRealtime() - t0} ms")
            if (ok) LaunchResult.Ok
            else LaunchResult.Failed("The engine found nothing to launch for ${clone.packageName} (slot ${clone.userId}).")
        } catch (t: Throwable) {
            CrashLog.note(DualSpaceApp.appContext, "launch ${clone.packageName} slot ${clone.userId}", t)
            LaunchResult.Failed(FailureText.describe(t))
        }
    }

    fun stop(clone: Clone) {
        DsLog.i(TAG, "stop ${clone.packageName} slot ${clone.userId}")
        runCatching { core.stopPackage(clone.packageName, clone.userId) }
            .onFailure { DsLog.w(TAG, "stopPackage threw", it) }
    }

    fun setFrozen(clone: Clone, frozen: Boolean) {
        if (frozen) stop(clone)
        clone.frozen = frozen
        CloneStore.update(clone)
    }

    fun clearData(clone: Clone) {
        DsLog.i(TAG, "clearData ${clone.packageName} slot ${clone.userId}")
        stop(clone)
        runCatching { core.clearPackage(clone.packageName, clone.userId) }
            .onFailure { DsLog.w(TAG, "clearPackage threw", it) }
    }

    fun delete(clone: Clone) {
        DsLog.i(TAG, "delete ${clone.packageName} slot ${clone.userId}")
        stop(clone)
        runCatching { core.uninstallPackageAsUser(clone.packageName, clone.userId) }
            .onFailure { DsLog.w(TAG, "uninstallPackageAsUser threw", it) }
        clone.customIconPath?.let { File(it).delete() }
        CloneStore.remove(clone.id)
        // Release the slot if it is now empty (GMS mirror aside).
        val stillUsed = CloneStore.all().any { it.userId == clone.userId }
        if (!stillUsed) {
            runCatching {
                if (core.isInstallGms(clone.userId)) core.uninstallGms(clone.userId)
                core.deleteUser(clone.userId)
            }.onFailure { DsLog.w(TAG, "releasing slot ${clone.userId} threw", it) }
        }
        runCatching { GmsLinker.refreshStatus() }
    }

    private fun ensureUser(userId: Int) {
        val exists = runCatching { core.users.any { it.id == userId } }.getOrDefault(false)
        if (!exists) runCatching { core.createUser(userId) }
            .onFailure { DsLog.w(TAG, "createUser($userId) threw", it) }
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

    fun totalStorageBytes(): Long = CloneStore.all().sumOf { runCatching { storageBytes(it) }.getOrDefault(0L) }

    // ---------------------------------------------------------------- icons

    /** Builds the (badged) icon. Decodes bitmaps: background thread; see [com.dualspace.clone.ui.IconCache]. */
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
