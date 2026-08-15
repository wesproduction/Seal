package com.junkfood.seal.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadUtilRuntimeTest {
    @Test
    fun externalJavaScriptRuntimeIsOnlyEnabledForCompatibleYtDlpVersions() {
        assertFalse(DownloadUtil.supportsExternalJavaScriptRuntime(null))
        assertFalse(DownloadUtil.supportsExternalJavaScriptRuntime("2025.10.22"))
        assertTrue(DownloadUtil.supportsExternalJavaScriptRuntime("2025.11.12"))
        assertTrue(DownloadUtil.supportsExternalJavaScriptRuntime("stable@2026.08.04"))
        assertTrue(DownloadUtil.supportsExternalJavaScriptRuntime("nightly@2026.08.14.232900"))
    }
}
