package com.dualspace.clone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dualspace.clone.data.CloneManager
import com.dualspace.clone.data.CloneStore
import com.dualspace.clone.data.GmsLinker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.niunaijun.blackbox.BlackBoxCore

/**
 * Instrumented acceptance tests — run on a real phone:
 *   ./gradlew connectedArm64DebugAndroidTest
 * They use the Android "Calculator"-class of app that exists on every device: the
 * Settings app (com.android.settings) is launchable everywhere and has no native libs.
 */
@RunWith(AndroidJUnit4::class)
class CloneIsolationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val pkg = "com.android.settings"

    @Test
    fun cloneSameAppTenTimesIntoSeparateSlots() {
        val created = (1..10).map {
            val r = CloneManager.createClone(ctx, pkg)
            assertTrue("clone $it failed: $r", r is CloneManager.Result.Ok)
            (r as CloneManager.Result.Ok).clone
        }
        // Every clone must sit in its own virtual user, and each must be installed there.
        assertEquals(10, created.map { it.userId }.toSet().size)
        created.forEach { assertTrue(BlackBoxCore.get().isInstalled(pkg, it.userId)) }
        created.forEach { CloneManager.delete(it) }
        assertEquals(0, CloneStore.byPackage(pkg).size)
    }

    @Test
    fun gmsIsLinkedBeforeFirstLaunch() {
        assumeTrue("phone has no Google Play Services", GmsLinker.isSupported())
        val r = CloneManager.createClone(ctx, pkg) as CloneManager.Result.Ok
        assertTrue("GMS must already be installed in slot ${r.clone.userId}", GmsLinker.isLinked(r.clone.userId))
        CloneManager.delete(r.clone)
    }
}
