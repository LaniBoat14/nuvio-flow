package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4K keyframes in strips: what a title is charged, and when it falls back to whole pictures. */
class StripDecodeTest {
    @Test fun firstStripCountIsKept() {
        assertEquals(8, StripDecode.next(-1, 8))
        assertEquals(8, StripDecode.next(8, 8))
        assertEquals(8, StripDecode.next(8, 4))
    }

    @Test fun oneWholeKeyframeEndsStripsForTheTitle() {
        assertEquals(0, StripDecode.next(-1, 0))
        assertEquals(0, StripDecode.next(8, 0))
        assertEquals(0, StripDecode.next(0, 8))
    }

    @Test fun onlyStrip4KIsChargedLess() {
        assertEquals(DecodeTier.SW_4K_STRIPS, StripDecode.tier(DecodeTier.SW_4K, 8))
        assertEquals(DecodeTier.SW_4K, StripDecode.tier(DecodeTier.SW_4K, -1))
        assertEquals(DecodeTier.SW_4K, StripDecode.tier(DecodeTier.SW_4K, 0))
        assertEquals(DecodeTier.SW_UP_TO_1080P, StripDecode.tier(DecodeTier.SW_UP_TO_1080P, 8))
    }

    @Test fun aKeyframeThatWillNotSplitIsChargedAWholeDecode() {
        assertEquals(DecodeTier.SW_4K, StripDecode.chargeFor(DecodeTier.SW_4K_STRIPS) { 0 })
        assertEquals(DecodeTier.SW_4K, StripDecode.chargeFor(DecodeTier.SW_4K_STRIPS) { 1 })
        assertEquals(DecodeTier.SW_4K_STRIPS, StripDecode.chargeFor(DecodeTier.SW_4K_STRIPS) { 8 })
        var looked = false
        assertEquals(DecodeTier.SW_4K, StripDecode.chargeFor(DecodeTier.SW_4K) { looked = true; 8 })
        assertEquals(false, looked)
    }

    @Test fun stripsCostAThirdOfAWholePictureAtMost() {
        assertTrue(DecodeTier.SW_4K_STRIPS.rssMb * 3 <= DecodeTier.SW_4K.rssMb)
        assertTrue(DecodeTier.SW_4K_STRIPS.addressSpaceMb < DecodeTier.SW_4K.addressSpaceMb)
    }
}
