package com.example.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLauncherToolTest {

    private lateinit var context: Context
    private lateinit var appLauncher: AppLauncherTool

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        appLauncher = AppLauncherTool(context)
    }

    @Test
    fun testResolveAliases_WhatsApp() {
        assertEquals("com.whatsapp", appLauncher.resolvePackage("WhatsApp"))
        assertEquals("com.whatsapp", appLauncher.resolvePackage("whatsapp kholo"))
        assertEquals("com.whatsapp", appLauncher.resolvePackage("wa"))
        assertEquals("com.whatsapp", appLauncher.resolvePackage("व्हाट्सएप"))
        assertEquals("com.whatsapp", appLauncher.resolvePackage("open whatsapp"))
    }

    @Test
    fun testResolveAliases_Instagram() {
        assertEquals("com.instagram.android", appLauncher.resolvePackage("Instagram"))
        assertEquals("com.instagram.android", appLauncher.resolvePackage("Insta"))
        assertEquals("com.instagram.android", appLauncher.resolvePackage("insta kholo"))
        assertEquals("com.instagram.android", appLauncher.resolvePackage("इन्स्टा"))
    }

    @Test
    fun testResolveAliases_YouTube() {
        assertEquals("com.google.android.youtube", appLauncher.resolvePackage("YouTube"))
        assertEquals("com.google.android.youtube", appLauncher.resolvePackage("yt"))
        assertEquals("com.google.android.youtube", appLauncher.resolvePackage("youtube kholo"))
        assertEquals("com.google.android.youtube", appLauncher.resolvePackage("यूट्यूब"))
    }

    @Test
    fun testResolveAliases_SettingsAndPhone() {
        assertEquals("com.android.settings", appLauncher.resolvePackage("Settings"))
        assertEquals("com.android.settings", appLauncher.resolvePackage("settings kholo"))
        assertEquals("com.google.android.dialer", appLauncher.resolvePackage("Phone"))
        assertEquals("com.google.android.dialer", appLauncher.resolvePackage("dialer"))
        assertEquals("com.google.android.dialer", appLauncher.resolvePackage("phone kholo"))
    }

    @Test
    fun testLaunchSettings_succeedsWithSystemIntent() = runBlocking {
        val result = appLauncher.launchApp("Settings")
        assertTrue("Settings launch must succeed", result is AppLaunchResult.Success)
        val success = result as AppLaunchResult.Success
        assertEquals("Settings", success.appName)
        assertEquals("com.android.settings", success.packageName)
        assertTrue("toAiResponse must start with SUCCESS", result.toAiResponse().startsWith("SUCCESS:"))
    }

    @Test
    fun testLaunchPhone_succeedsWithDialerIntent() = runBlocking {
        val result = appLauncher.launchApp("phone kholo")
        assertTrue("Phone launch must succeed", result is AppLaunchResult.Success)
        val success = result as AppLaunchResult.Success
        assertEquals("Phone", success.appName)
        assertEquals("com.android.dialer", success.packageName)
        assertTrue("toAiResponse must start with SUCCESS", result.toAiResponse().startsWith("SUCCESS:"))
    }

    @Test
    fun testMissingApp_returnsNotInstalledResult() = runBlocking {
        val result = appLauncher.launchApp("totally_unknown_custom_app_12345")
        assertTrue("Unknown app must return AppNotInstalled", result is AppLaunchResult.AppNotInstalled)
        assertTrue("Ai response must start with NOT_INSTALLED", result.toAiResponse().startsWith("NOT_INSTALLED:"))
        assertTrue("Response must mention Play Store", result.toAiResponse().contains("Play Store"))
    }
}
