package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSpeedupLazyMemoryTest {

    private val mb = 1024L * 1024L

    @Test
    fun `one request more only with the switch on and room below the server budget`() {
        assertEquals(0, AutoSpeedup.extraRequest(on = false, downloads = 5, readAhead = 0))
        assertEquals(1, AutoSpeedup.extraRequest(on = true, downloads = 5, readAhead = 0))
        assertEquals(1, AutoSpeedup.extraRequest(on = true, downloads = 5, readAhead = 1))
        assertEquals(0, AutoSpeedup.extraRequest(on = true, downloads = 7, readAhead = 0))
        assertEquals(0, AutoSpeedup.extraRequest(on = true, downloads = 5, readAhead = 2))
        assertEquals(0, AutoSpeedup.extraRequest(on = true, downloads = 1, readAhead = 0))
    }

    @Test
    fun `memory follows the bytes on boxes of 3 GB or more, not on 2 GB boxes`() {
        assertEquals(false, AutoSpeedup.lazyMemoryFor(AutoSpeedup.limitsFor(1_900L * mb)))
        assertEquals(true, AutoSpeedup.lazyMemoryFor(AutoSpeedup.limitsFor(2_900L * mb)))
        assertEquals(true, AutoSpeedup.lazyMemoryFor(AutoSpeedup.limitsFor(4_000L * mb)))
        assertEquals(false, AutoSpeedup.lazyMemoryFor(AutoSpeedup.limitsFor(0L)))
    }

    @Test
    fun `the pieces may hold what the requests could hold before, within a learned limit`() {
        assertEquals(48L * mb, AutoSpeedup.heldLimitFor(depth = 5, requestLimit = Int.MAX_VALUE, pieceBytes = 8L * mb))
        assertEquals(108L * mb, AutoSpeedup.heldLimitFor(depth = 8, requestLimit = Int.MAX_VALUE, pieceBytes = 12L * mb))
        assertEquals(96L * mb, AutoSpeedup.heldLimitFor(depth = 8, requestLimit = 7, pieceBytes = 12L * mb))
    }
}
