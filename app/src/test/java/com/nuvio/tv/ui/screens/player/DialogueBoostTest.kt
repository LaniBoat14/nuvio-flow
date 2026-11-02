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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DialogueBoostTest {

    private fun dynamics(channels: Int, centerDb: Float, level: NightModeLevel = NightModeLevel.OFF) =
        PcmDynamics().apply {
            setCenterGainDb(centerDb)
            setNightMode(level)
            configure(48_000, channels)
            prepareBuffer()
        }

    @Test
    fun `only 5_1 and 7_1 have a centre the code can place`() {
        for (channels in 1..8) {
            val expected = if (channels == 6 || channels == 8) 2 else -1
            assertEquals("$channels", expected, PcmDynamics.centerChannelIndex(channels))
        }
    }

    @Test
    fun `boost raises only the centre channel`() {
        val d = dynamics(channels = 6, centerDb = 6f)
        val frame = FloatArray(6)
        repeat(4_800) {
            frame.fill(0.05f)
            d.processFrame(frame)
        }
        assertEquals(0.05f * 1.9953f, frame[2], 0.001f)
        for (c in listOf(0, 1, 3, 4, 5)) assertEquals(0.05f, frame[c], 1e-6f)
    }

    @Test
    fun `stereo is left alone even with a boost set`() {
        val d = dynamics(channels = 2, centerDb = 12f)
        assertFalse(d.isEnabled())
        val frame = floatArrayOf(0.3f, -0.3f)
        repeat(1_000) {
            frame[0] = 0.3f; frame[1] = -0.3f
            d.processFrame(frame)
        }
        assertEquals(0.3f, frame[0], 0f)
        assertEquals(-0.3f, frame[1], 0f)
    }

    @Test
    fun `full boost on a loud centre never passes the ceiling`() {
        for (channels in listOf(6, 8)) {
            val d = dynamics(channels = channels, centerDb = 12f, level = NightModeLevel.LIGHT)
            val frame = FloatArray(channels)
            for (i in 0 until 48_000) {
                val s = sin(2.0 * PI * 200.0 * i / 48_000).toFloat()
                for (c in 0 until channels) frame[c] = 0.9f * s
                d.processFrame(frame)
                for (v in frame) assertTrue("$v", abs(v) <= PcmDynamics.LIMITER_CEILING + 1e-6f)
            }
        }
    }

    @Test
    fun `changing the boost glides instead of jumping`() {
        val d = dynamics(channels = 6, centerDb = 0f)
        val frame = FloatArray(6)
        var previous = 0f
        var maxStep = 0f
        for (i in 0 until 48_000) {
            if (i % 4_800 == 0) {
                d.setCenterGainDb(((i / 4_800) % 13).toFloat())
                d.prepareBuffer()
            }
            frame.fill(0.05f)
            d.processFrame(frame)
            if (i > 0) maxStep = max(maxStep, abs(frame[2] - previous))
            previous = frame[2]
        }
        assertTrue("step $maxStep", maxStep < 0.002f)
    }

    private fun max(a: Float, b: Float) = if (a > b) a else b

    @Test
    fun `processor joins the chain only when the decoded sound has a centre`() {
        val p = GainAudioProcessor()
        p.setCenterGainDb(6)
        p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertFalse(p.isActive())
        p.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_FLOAT))
        assertTrue(p.isActive())
        assertTrue(p.canBoostCenter())
        p.setCenterGainDb(0)
        assertFalse(p.isActive())
    }

    @Test
    fun `processor reports channel count changes`() {
        val p = GainAudioProcessor()
        val seen = mutableListOf<Int>()
        p.onPcmChannelCountChanged = { seen += it }
        p.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        p.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(listOf(6, 2), seen)
    }

    @Test
    fun `processor boosts the centre of 16-bit 5_1`() {
        val p = GainAudioProcessor()
        p.setCenterGainDb(12)
        p.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        p.flush()
        val frames = 4_800
        val input = ByteBuffer.allocateDirect(frames * 6 * 2).order(ByteOrder.nativeOrder())
        repeat(frames * 6) { input.putShort(1_000) }
        input.flip()
        p.queueInput(input)
        val out = p.getOutput().order(ByteOrder.nativeOrder())
        val last = ShortArray(6)
        while (out.hasRemaining()) for (c in 0 until 6) last[c] = out.short
        assertEquals(3_981, last[2].toInt(), 20)
        assertEquals(1_000, last[0].toInt())
        assertEquals(1_000, last[3].toInt())
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("$actual vs $expected", abs(actual - expected) <= tolerance)

    @Test
    fun `availability says why it cannot work`() {
        fun state(
            mpv: Boolean = false,
            bypass: Boolean = false,
            track: Int = 6,
            ffmpeg: Boolean = false,
            pcm: Int = 0,
            mpvOut: Int = 0
        ) = dialogueBoostState(mpv, bypass, track, ffmpeg, pcm, mpvOut)

        assertEquals(DialogueBoostState.PASSTHROUGH, state(bypass = true))
        assertEquals(DialogueBoostState.STEREO_TRACK, state(track = 2, pcm = 2))
        assertEquals(DialogueBoostState.AVAILABLE, state(ffmpeg = true, pcm = 2))
        assertEquals(DialogueBoostState.AVAILABLE, state(pcm = 6))
        assertEquals(DialogueBoostState.AVAILABLE, state(pcm = 8, track = 8))
        assertEquals(DialogueBoostState.MIXED_TO_STEREO, state(pcm = 2))
        assertEquals(DialogueBoostState.MIXED_TO_STEREO, state(pcm = 0))
        assertEquals(DialogueBoostState.AVAILABLE, state(mpv = true, bypass = true, mpvOut = 2))
        assertEquals(DialogueBoostState.MPV_MULTICHANNEL, state(mpv = true, mpvOut = 8))
        assertEquals(DialogueBoostState.STEREO_TRACK, state(mpv = true, track = 2, mpvOut = 2))
    }

    @Test
    fun `mpv gets the same centre level as the ffmpeg downmix`() {
        assertEquals("lfe_mix_level=0.5000", mpvSwresampleOptions(0))
        assertEquals("lfe_mix_level=0.5000,center_mix_level=1.4109", mpvSwresampleOptions(6))
        assertEquals("lfe_mix_level=0.5000,center_mix_level=2.8150", mpvSwresampleOptions(12))
    }
}
