package com.nuvio.tv.launcher

import com.nuvio.tv.domain.model.AppIconChoice
import com.nuvio.tv.domain.model.AppIconLook
import com.nuvio.tv.domain.model.AppIconOption
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconEntriesTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val manifest = File(main, "AndroidManifest.xml").readText()

    private fun entry(className: String): String {
        val name = className.removePrefix("com.nuvio.tv")
        val start = manifest.indexOf("android:name=\"$name\"")
        assertTrue("$name missing from the manifest", start >= 0)
        return manifest.substring(start, manifest.indexOf("</activity>", start))
    }

    @Test
    fun classicEntriesKeepTheirNames() {
        assertEquals("com.nuvio.tv.launcher.AppIconDefault", launcherClassName(AppIconChoice(AppIconOption.ORIGINAL)))
        assertEquals("com.nuvio.tv.launcher.AppIconCopper", launcherClassName(AppIconChoice(AppIconOption.COPPER)))
        assertEquals(
            "com.nuvio.tv.launcher.AppIconLetteringClearEmeraldGreen",
            launcherClassName(AppIconChoice(AppIconOption.EMERALD_GREEN, AppIconLook.CARD_LETTERING, true))
        )
    }

    @Test
    fun everyChoiceHasItsOwnLauncherEntry() {
        val names = AppIconChoice.all.map(::launcherClassName)
        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            val block = entry(name)
            assertTrue(name, block.contains("android.intent.category.LEANBACK_LAUNCHER"))
            val expected = if (name.endsWith("AppIconDefault")) "android:enabled=\"true\"" else "android:enabled=\"false\""
            assertTrue(name, block.contains(expected))
        }
    }

    @Test
    fun everyBannerComesInBothSizes() {
        AppIconChoice.all.forEach { choice ->
            val banner = Regex("android:banner=\"@mipmap/([a-z_]+)\"").find(entry(launcherClassName(choice)))!!.groupValues[1]
            listOf("mipmap-xhdpi", "mipmap-xxxhdpi").forEach { dir ->
                assertTrue("$dir/$banner", File(main, "res/$dir/$banner.webp").isFile)
            }
        }
    }
}
