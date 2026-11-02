package com.nuvio.tv.core.player.dvmkv

import org.junit.Assert.assertEquals
import org.junit.Test

class MatroskaIndexReadLineTest {

    @Test
    fun `index read line gives size, cue points, time and source reads`() {
        assertEquals(
            "[mkv_index] 1552 KB, 9210 cue points, 1632 ms, 1184220 source reads",
            MatroskaExtractor.indexReadLine(1_589_760L, 9_210, 1_632L, 1_184_220L),
        )
    }
}
