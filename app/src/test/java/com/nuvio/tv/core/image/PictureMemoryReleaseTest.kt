package com.nuvio.tv.core.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureMemoryReleaseTest {
    private val mb = 1024L * 1024L

    @Test
    fun `only boxes sold as 2 GB or less let pictures go before playback`() {
        assertTrue(PictureMemoryRelease.releasesBeforePlayback(1_900L * mb))
        assertTrue(PictureMemoryRelease.releasesBeforePlayback(0L))
        assertFalse(PictureMemoryRelease.releasesBeforePlayback(2_900L * mb))
        assertFalse(PictureMemoryRelease.releasesBeforePlayback(3_800L * mb))
    }
}
