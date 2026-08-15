package com.junkfood.seal

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun appContextUsesTheDebugApplicationId() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.junkfood.seal.debug", appContext.packageName)
    }

    @Test
    fun bundledQuickJsRunsOnTheDevice() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val quickJs = File(appContext.applicationInfo.nativeLibraryDir, "libqjs.so")
        assertTrue("QuickJS was not extracted", quickJs.isFile)

        val process =
            ProcessBuilder(quickJs.absolutePath, "--version").redirectErrorStream(true).start()
        assertTrue("QuickJS did not exit", process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertEquals("0.16.1", process.inputStream.bufferedReader().readText().trim())
    }

    @Test
    fun appIsAnAndroidTextShareTarget() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://www.reddit.com/comments/abc123")
            }
        val matches =
            appContext.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )

        assertTrue(
            matches.any {
                it.activityInfo.packageName == appContext.packageName &&
                    it.activityInfo.name == QuickDownloadActivity::class.java.name
            }
        )

        val activityInfo =
            appContext.packageManager.getActivityInfo(
                ComponentName(appContext, QuickDownloadActivity::class.java),
                0,
            )
        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, activityInfo.launchMode)
        assertNull(activityInfo.taskAffinity)
        assertTrue(activityInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }
}
