package com.nuvio.tv.ui.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArcStartHandoffTest {

    @Test
    fun `only Android 8 to 11 carry the mark in the start window`() {
        assertFalse(ArcStartHandoff.windowCarriesMark(24))
        assertFalse(ArcStartHandoff.windowCarriesMark(25))
        assertTrue(ArcStartHandoff.windowCarriesMark(26))
        assertTrue(ArcStartHandoff.windowCarriesMark(30))
        assertFalse(ArcStartHandoff.windowCarriesMark(31))
        assertFalse(ArcStartHandoff.windowCarriesMark(34))
    }

    @Test
    fun `hand-off starts with the plate at full strength`() {
        assertEquals(1f, ArcStartHandoff.plateFade(0f, handoff = true), 0f)
        assertEquals(0f, ArcStartHandoff.plateFade(0f, handoff = false), 0f)
        assertTrue(ArcStartHandoff.plateFade(0.45f, handoff = false) in 0.4f..0.6f)
        assertEquals(1f, ArcStartHandoff.plateFade(0.9f, handoff = false), 0f)
    }

    @Test
    fun `hand-off fades the whole frame in over 300 ms`() {
        assertEquals(0f, ArcStartHandoff.frameAlpha(0f, handoff = true, reducedMotion = false), 0f)
        assertTrue(ArcStartHandoff.frameAlpha(0.15f, handoff = true, reducedMotion = false) in 0.4f..0.6f)
        assertEquals(1f, ArcStartHandoff.frameAlpha(0.3f, handoff = true, reducedMotion = false), 0f)
        assertEquals(0.3f, ArcStartHandoff.CrossfadeSeconds, 0f)
    }

    @Test
    fun `without a hand-off the frame is full at once unless motion is reduced`() {
        assertEquals(1f, ArcStartHandoff.frameAlpha(0f, handoff = false, reducedMotion = false), 0f)
        assertEquals(0f, ArcStartHandoff.frameAlpha(0f, handoff = false, reducedMotion = true), 0f)
        assertEquals(1f, ArcStartHandoff.frameAlpha(0.3f, handoff = false, reducedMotion = true), 0f)
    }

    @Test
    fun `every start window puts the launcher mark on the intro's logo plate`() {
        val dir = resDir("drawable-v26")
        val windows = dir.listFiles { f -> f.name.matches(Regex("""start_window(_[a-z_]+)?\.xml""")) && f.name != "start_window_stage_fill.xml" }!!
        assertEquals(16, windows.size)
        for (file in windows) {
            val xml = file.readText()
            fun inset(name: String) = Regex("android:inset$name=\"([0-9.]+)%\"").find(xml)!!.groupValues[1].toFloat() / 100f
            val left = inset("Left") * StageW
            val top = inset("Top") * StageH
            val size = StageW - left - inset("Right") * StageW
            assertEquals(file.name, size, StageH - top - inset("Bottom") * StageH, 1f)
            // The mark inside the 432 px launcher art and inside the intro's 640 px plate, drawn at 700, 180, 520.
            val k = size / 432f
            val p = 520f / 640f
            assertEquals(file.name, 700f + 30f * p, left + 96f * k, 2f)
            assertEquals(file.name, 700f + 588f * p, left + 326f * k, 2f)
            assertEquals(file.name, 180f + (17f + 562f) / 2f * p, top + (91f + 315f) / 2f * k, 2f)
            val suffix = file.name.removePrefix("start_window").removeSuffix(".xml")
            assertTrue(file.name, xml.contains("@drawable/arc_launcher_foreground$suffix\""))
        }
    }

    @Test
    fun `each launcher colour opens on its own start window`() {
        val splash = resDir("values-v31").resolve("themes.xml").readText()
        val window = resDir("values").resolve("themes.xml").readText()
        val style = Regex("""<style name="(Theme\.MyApplication\.Launcher[A-Za-z.]*)"[^>]*>\s*(?:<item[^>]*>[^<]*</item>\s*)*?<item name="([^"]+)">@drawable/([a-z_]+)</item>""")
        fun icons(text: String, attr: String) = style.findAll(text).filter { it.groupValues[2] == attr }
            .associate { it.groupValues[1] to it.groupValues[3] }
        val splashIcons = icons(splash, "windowSplashScreenAnimatedIcon")
        val windowArt = icons(window, "android:windowBackground")
        assertEquals(16, splashIcons.size)
        assertEquals(splashIcons.keys, windowArt.keys)
        for ((name, icon) in splashIcons) {
            assertEquals(name, icon.replace("arc_launcher_foreground", "start_window"), windowArt[name])
        }
    }

    private fun resDir(name: String): File =
        listOf(File("src/main/res", name), File("app/src/main/res", name)).first { it.isDirectory }

    private companion object {
        const val StageW = 1920f
        const val StageH = 1080f
    }
}
