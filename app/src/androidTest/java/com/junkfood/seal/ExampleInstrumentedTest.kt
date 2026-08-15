package com.junkfood.seal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
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

        val process = ProcessBuilder(quickJs.absolutePath, "--version").redirectErrorStream(true).start()
        assertTrue("QuickJS did not exit", process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertEquals("0.16.1", process.inputStream.bufferedReader().readText().trim())
    }
}
