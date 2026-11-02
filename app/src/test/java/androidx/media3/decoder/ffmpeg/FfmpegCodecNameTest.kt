package androidx.media3.decoder.ffmpeg

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class FfmpegCodecNameTest {

    @Test
    fun `dts express uses the dca decoder`() {
        assertEquals("dca", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_DTS_EXPRESS))
    }

    @Test
    fun `other dts variants keep their mapping`() {
        assertEquals("dca", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_DTS))
        assertEquals("dca", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_DTS_HD))
        assertEquals(null, FfmpegLibrary.getCodecName(MimeTypes.AUDIO_DTS_X))
    }
}
