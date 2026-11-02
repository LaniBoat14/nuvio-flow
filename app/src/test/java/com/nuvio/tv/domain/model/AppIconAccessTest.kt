package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconAccessTest {
    private val original = AppIconChoice(AppIconOption.ORIGINAL)

    private fun usable(entitlements: CosmeticEntitlements, look: AppIconLook, selected: AppIconChoice = original) =
        AppIconOption.entries.filter { canUseAppIcon(entitlements, AppIconChoice.of(it, look, false), selected) }

    @Test
    fun supporterColoursNeedTheirTheme() {
        val free = listOf(AppIconOption.ORIGINAL, AppIconOption.EMERALD, AppIconOption.COPPER, AppIconOption.GLASS) +
            listOf(AppIconOption.CRIMSON, AppIconOption.OCEAN, AppIconOption.VIOLET, AppIconOption.EMERALD_GREEN,
                AppIconOption.AMBER, AppIconOption.ROSE, AppIconOption.WHITE)
        AppIconLook.entries.forEach { look -> assertEquals(free, usable(CosmeticEntitlements.None, look)) }
    }

    @Test
    fun unlockedSupporterThemeUnlocksItsIconInEveryLook() {
        val gold = CosmeticEntitlements(setOf(CosmeticEntitlement.GOLD_THEME))
        AppIconLook.entries.forEach { look ->
            val icons = usable(gold, look)
            assertTrue(AppIconOption.GOLD in icons)
            assertFalse(AppIconOption.JADE in icons)
        }
    }

    @Test
    fun iconInUseStaysListedButNotItsOtherLooks() {
        val inUse = AppIconChoice(AppIconOption.ROSE_GOLD)
        assertTrue(AppIconOption.ROSE_GOLD in usable(CosmeticEntitlements.None, AppIconLook.CLASSIC, inUse))
        assertFalse(AppIconOption.ROSE_GOLD in usable(CosmeticEntitlements.None, AppIconLook.CARD, inUse))
        assertFalse(AppIconOption.GRAPHITE in usable(CosmeticEntitlements.None, AppIconLook.CLASSIC, inUse))
    }

    @Test
    fun classicIconsHaveNoTransparentBanner() {
        assertEquals(AppIconChoice(AppIconOption.COPPER), AppIconChoice.of(AppIconOption.COPPER, AppIconLook.CLASSIC, true))
        assertTrue(AppIconChoice.of(AppIconOption.COPPER, AppIconLook.CARD, true).transparent)
    }

    @Test
    fun everyColourComesInFiveVariants() {
        assertEquals(80, AppIconChoice.all.size)
        assertEquals(80, AppIconChoice.all.toSet().size)
        assertEquals(16, AppIconChoice.all.count { it.look == AppIconLook.CLASSIC })
    }
}
