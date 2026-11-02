package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.nuvio.tv.data.local.NightModeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GainAudioProcessorTest {

    private fun processor(
        encoding: Int = C.ENCODING_PCM_16BIT,
        channels: Int = 2,
        gainDb: Int = 0,
        level: NightModeLevel = NightModeLevel.OFF
    ) = GainAudioProcessor().apply {
        setGainDb(gainDb)
        setNightMode(level)
        configure(AudioProcessor.AudioFormat(48_000, channels, encoding))
        flush()
    }

    private fun pcm16(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it) }
            flip()
        }

    private fun GainAudioProcessor.run16(input: ByteBuffer): ShortArray {
        queueInput(input)
        val out = getOutput().order(ByteOrder.nativeOrder())
        return ShortArray(out.remaining() / 2) { out.short }
    }

    @Test
    fun `stays out of the audio path when everything is off`() {
        assertFalse(processor().isActive())
        assertTrue(processor(level = NightModeLevel.MEDIUM).isActive())
        assertTrue(processor(gainDb = 4).isActive())
    }

    @Test
    fun `formats it does not handle are left alone`() {
        for (encoding in listOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_8BIT)) {
            assertFalse("$encoding", processor(encoding = encoding, gainDb = 6, level = NightModeLevel.STRONG).isActive())
        }
    }

    @Test
    fun `16-bit gain keeps the sample count and channel order`() {
        val p = processor(gainDb = 6)
        val out = p.run16(pcm16(1000, -500, 2000, -1000))
        assertEquals(4, out.size)
        // 6 dB is about x2; the first samples are still ramping up from unity
        assertTrue(out[0] in 1000..2000)
        assertTrue(out[1] in -1000..-500)
        assertTrue(out[2] > 0 && out[3] < 0)
    }

    @Test
    fun `16-bit loud input is limited instead of clipped`() {
        val p = processor(gainDb = 10)
        val samples = ShortArray(48_000) { i -> (if (i % 2 == 0) 30_000 else -30_000).toShort() }
        val out = p.run16(pcm16(*samples))
        val ceiling = (PcmDynamics.LIMITER_CEILING * 32768f).toInt() + 1
        assertTrue(out.all { abs(it.toInt()) <= ceiling })
    }

    @Test
    fun `float output stays finite and in range`() {
        val p = processor(encoding = C.ENCODING_PCM_FLOAT, channels = 6, gainDb = 10, level = NightModeLevel.STRONG)
        val input = ByteBuffer.allocateDirect(6 * 4 * 4_800).order(ByteOrder.nativeOrder())
        repeat(6 * 4_800) { i -> input.putFloat(if (i % 7 == 0) 3f else -0.9f) }
        input.flip()
        p.queueInput(input)
        val out = p.getOutput().order(ByteOrder.nativeOrder())
        assertEquals(6 * 4 * 4_800, out.remaining())
        while (out.hasRemaining()) {
            val v = out.float
            assertTrue("$v", v.isFinite() && abs(v) <= 1f)
        }
    }

    @Test
    fun `flush starts the next stream from a clean state`() {
        val p = processor(level = NightModeLevel.STRONG)
        p.run16(pcm16(*ShortArray(9_600) { 29_000 }))
        p.flush()
        val out = p.run16(pcm16(300, 300))
        assertTrue("${out[0]}", abs(out[0] - 300) <= 3)
    }

    @Test
    fun `a new sample rate or channel count is picked up on configure`() {
        val p = processor(level = NightModeLevel.LIGHT)
        p.configure(AudioProcessor.AudioFormat(44_100, 6, C.ENCODING_PCM_16BIT))
        p.flush()
        val out = p.run16(pcm16(*ShortArray(6 * 100) { 100 }))
        assertEquals(600, out.size)
    }

    @Test
    fun `turning everything off takes it out at the next flush`() {
        val p = processor(level = NightModeLevel.MEDIUM)
        assertTrue(p.isActive())
        p.setNightMode(NightModeLevel.OFF)
        assertFalse(p.isProcessingEnabled())
        assertFalse(p.isActive())
    }
}
