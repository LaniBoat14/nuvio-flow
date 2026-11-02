package com.nuvio.tv.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureDietTest {
    private val mib = 1024L * 1024
    private val fireStickRam = 1_946L * mib
    private val fireStickHeapBudget = (0.15 * 384 * mib).toLong()

    @Test fun smallBoxCacheIsCappedBelowTheHeapBudget() {
        assertEquals(SMALL_BOX_PICTURE_CACHE_BYTES, pictureMemoryCacheBytes(fireStickHeapBudget, fireStickRam))
        assertEquals(30L * mib, pictureMemoryCacheBytes(30L * mib, fireStickRam))
    }

    @Test fun bigBoxesKeepTodaysBudget() {
        val am9Ram = 3_900L * mib
        val budget = (0.25 * 384 * mib).toLong()
        assertEquals(homeArtworkMemoryCacheBytes(budget, am9Ram), pictureMemoryCacheBytes(budget, am9Ram))
        val threeGb = 2_900L * mib
        assertEquals(homeArtworkMemoryCacheBytes(budget, threeGb), pictureMemoryCacheBytes(budget, threeGb))
    }

    @Test fun smallBoxCacheStillHoldsAFullScreenBackdrop() {
        val cache = ArtworkMemoryCache(pictureMemoryCacheBytes(fireStickHeapBudget, fireStickRam))
        assertTrue(cache.maxSize - cache.posterMaxSize >= 1920L * 1080 * 4)
        val poster = 252L * 378 * 4
        assertTrue(cache.posterMaxSize / poster >= 60)
    }

    @Test fun decodeLimitIsTheScreensLongerSide() {
        assertEquals(1920, pictureMaxBitmapSide(1920, 1080))
        assertEquals(1920, pictureMaxBitmapSide(1080, 1920))
        assertEquals(3840, pictureMaxBitmapSide(3840, 2160))
        assertEquals(1280, pictureMaxBitmapSide(1280, 720))
    }

    @Test fun decodeLimitNeverDropsBelowA720pScreenOrGoesMissing() {
        assertEquals(1280, pictureMaxBitmapSide(960, 540))
        assertEquals(1920, pictureMaxBitmapSide(0, 0))
    }

    @Test fun decodeLimitNeverCutsAnythingDrawnOnScreen() {
        val side = pictureMaxBitmapSide(1920, 1080)
        assertTrue(side >= 1920 && side >= 1080)
    }
}
