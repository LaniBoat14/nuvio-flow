package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NightModeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

class PcmDynamicsTest {

    private fun dynamics(rate: Int = 48_000, channels: Int = 2, gainDb: Float = 0f, level: NightModeLevel) =
        PcmDynamics().apply {
            setGainDb(gainDb)
            setNightMode(level)
            configure(rate, channels)
        }

    /** Runs [seconds] of a sine at [amplitude] through [d] and returns the output peak of the last half. */
    private fun steadyPeak(
        d: PcmDynamics,
        amplitude: Float,
        seconds: Float = 2f,
        rate: Int = 48_000,
        channels: Int = 2
    ): Float {
        val frames = (seconds * rate).toInt()
        val frame = FloatArray(channels)
        var peak = 0f
        d.prepareBuffer()
        for (i in 0 until frames) {
            val v = amplitude * sin(2.0 * PI * 1000.0 * i / rate).toFloat()
            frame.fill(v)
            d.processFrame(frame)
            if (i > frames / 2) peak = max(peak, abs(frame[0]))
        }
        return peak
    }

    private fun db(v: Float) = 20f * log10(v)

    @Test
    fun `off with no gain is not enabled`() {
        assertFalse(dynamics(level = NightModeLevel.OFF).isEnabled())
        assertTrue(dynamics(level = NightModeLevel.LIGHT).isEnabled())
        assertTrue(dynamics(gainDb = 3f, level = NightModeLevel.OFF).isEnabled())
    }

    @Test
    fun `off passes the signal through unchanged`() {
        val d = dynamics(level = NightModeLevel.OFF)
        d.prepareBuffer()
        val frame = FloatArray(2)
        for (i in 0 until 48_000) {
            val v = 0.95f * sin(i * 0.05).toFloat()
            frame[0] = v
            frame[1] = -v
            d.processFrame(frame)
            assertEquals(v, frame[0], 0f)
            assertEquals(-v, frame[1], 0f)
        }
    }

    @Test
    fun `night mode lifts quiet passages, only strong turns the loudest down`() {
        for (level in listOf(NightModeLevel.LIGHT, NightModeLevel.MEDIUM, NightModeLevel.STRONG)) {
            val loudChange = db(steadyPeak(dynamics(level = level), 0.85f)) - db(0.85f)
            val talkChange = db(steadyPeak(dynamics(level = level), 0.1f)) - db(0.1f)
            val quietChange = db(steadyPeak(dynamics(level = level), 0.01f)) - db(0.01f)
            if (level == NightModeLevel.STRONG) {
                assertTrue("$level loud $loudChange", loudChange in -7f..-3f)
            } else {
                assertTrue("$level loud $loudChange", loudChange > -0.1f)
            }
            assertTrue("$level talk $talkChange vs loud $loudChange", talkChange > loudChange)
            assertTrue("$level quiet $quietChange vs loud $loudChange", quietChange > loudChange + 5f)
        }
    }

    @Test
    fun `bass under quiet speech keeps its level`() {
        // a loud low rumble, the case that used to be pulled down by 7 to 16 dB
        for (level in listOf(NightModeLevel.LIGHT, NightModeLevel.MEDIUM, NightModeLevel.STRONG)) {
            val d = dynamics(level = level)
            val rate = 48_000
            val frame = FloatArray(2)
            var peak = 0f
            d.prepareBuffer()
            for (i in 0 until 2 * rate) {
                val v = 0.5f * sin(2.0 * PI * 40.0 * i / rate).toFloat() + 0.03f * sin(2.0 * PI * 300.0 * i / rate).toFloat()
                frame.fill(v)
                d.processFrame(frame)
                if (i > rate) peak = max(peak, abs(frame[0]))
            }
            val allowed = if (level == NightModeLevel.STRONG) 1.5f else 0.1f
            assertTrue("$level rumble ${db(peak) - db(0.53f)}", db(peak) >= db(0.53f) - allowed)
        }
    }

    @Test
    fun `stronger levels even things out more`() {
        fun spread(level: NightModeLevel) =
            db(steadyPeak(dynamics(level = level), 0.9f)) - db(steadyPeak(dynamics(level = level), 0.01f))
        val light = spread(NightModeLevel.LIGHT)
        val medium = spread(NightModeLevel.MEDIUM)
        val strong = spread(NightModeLevel.STRONG)
        assertTrue("$light $medium $strong", light > medium && medium > strong)
    }

    @Test
    fun `near silence is not lifted`() {
        val change = db(steadyPeak(dynamics(level = NightModeLevel.STRONG), 0.0002f)) - db(0.0002f)
        assertTrue("change $change", change < 0.5f)
    }

    @Test
    fun `output never passes the ceiling with gain and night mode combined`() {
        for (rate in listOf(44_100, 48_000, 96_000)) {
            for (channels in listOf(1, 2, 6, 8)) {
                val d = dynamics(rate = rate, channels = channels, gainDb = 10f, level = NightModeLevel.STRONG)
                d.prepareBuffer()
                val frame = FloatArray(channels)
                for (i in 0 until rate) {
                    // a sudden full-scale burst after quiet, the worst case for a feed-forward limiter
                    val amplitude = if (i < rate / 2) 0.01f else 1f
                    for (c in 0 until channels) frame[c] = amplitude * sin(2.0 * PI * 440.0 * (i + c) / rate).toFloat()
                    d.processFrame(frame)
                    for (c in 0 until channels) {
                        assertTrue("rate=$rate ch=$channels i=$i ${frame[c]}", abs(frame[c]) <= PcmDynamics.LIMITER_CEILING + 1e-6f)
                    }
                }
            }
        }
    }

    @Test
    fun `gain alone no longer hard clips`() {
        val d = dynamics(gainDb = 10f, level = NightModeLevel.OFF)
        d.prepareBuffer()
        val frame = FloatArray(2)
        var clippedRun = 0
        var longestRun = 0
        for (i in 0 until 48_000) {
            frame.fill(0.8f * sin(2.0 * PI * 100.0 * i / 48_000).toFloat())
            d.processFrame(frame)
            clippedRun = if (abs(frame[0]) >= 0.999f) clippedRun + 1 else 0
            longestRun = max(longestRun, clippedRun)
        }
        assertEquals(0, longestRun)
    }

    @Test
    fun `garbage input gives finite bounded output`() {
        val d = dynamics(channels = 2, gainDb = 6f, level = NightModeLevel.MEDIUM)
        d.prepareBuffer()
        val bad = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e30f, -1e30f, 0f)
        val frame = FloatArray(2)
        for (i in 0 until 10_000) {
            frame[0] = bad[i % bad.size]
            frame[1] = 0.5f
            d.processFrame(frame)
            for (v in frame) assertTrue("$v", v.isFinite() && abs(v) <= 1f)
        }
        // and it recovers straight away
        frame.fill(0.05f)
        d.processFrame(frame)
        assertTrue(frame[1].isFinite())
    }

    @Test
    fun `changing level mid-stream has no audible step`() {
        val d = dynamics(channels = 1, level = NightModeLevel.OFF)
        val frame = FloatArray(1)
        var previous = 0f
        var maxStep = 0f
        for (i in 0 until 96_000) {
            if (i % 4_800 == 0) {
                d.setNightMode(NightModeLevel.entries[(i / 4_800) % 4])
                d.setGainDb(((i / 9_600) % 3) * 4f)
                d.prepareBuffer()
            }
            // DC input shows any gain step directly as a jump in the output
            frame[0] = 0.3f
            d.processFrame(frame)
            if (i > 0) maxStep = max(maxStep, abs(frame[0] - previous))
            previous = frame[0]
        }
        assertTrue("step $maxStep", maxStep < 0.01f)
    }

    @Test
    fun `reset clears the compressor memory`() {
        val d = dynamics(level = NightModeLevel.STRONG)
        steadyPeak(d, 0.9f, seconds = 1f)
        d.reset()
        d.prepareBuffer()
        val frame = floatArrayOf(0.01f, 0.01f)
        d.processFrame(frame)
        // straight after a reset the gain starts from unity, not from the loud passage before the seek
        assertEquals(0.01f, frame[0], 0.0005f)
    }

    @Test
    fun `all channels get the same gain so the balance is kept`() {
        val d = dynamics(channels = 6, level = NightModeLevel.STRONG)
        d.prepareBuffer()
        val levels = floatArrayOf(0.9f, 0.9f, 0.5f, 0.1f, 0.3f, 0.3f)
        val frame = FloatArray(6)
        for (i in 0 until 48_000) {
            val s = sin(2.0 * PI * 300.0 * i / 48_000).toFloat()
            for (c in 0 until 6) frame[c] = levels[c] * s
            d.processFrame(frame)
            if (abs(s) > 0.5f) {
                for (c in 1 until 6) {
                    assertEquals(levels[c] / levels[0], frame[c] / frame[0], 1e-4f)
                }
            }
        }
    }

    @Test
    fun `static curve matches the presets`() {
        val medium = PcmDynamics.presetFor(NightModeLevel.MEDIUM)
        // well below threshold: only the make-up gain
        assertEquals(10f, PcmDynamics.compressorTargetDb(0.01f, medium), 0.01f)
        // 15 dB over a 3:1 threshold: 10 dB down, cancelling the 10 dB make-up
        assertEquals(0f, PcmDynamics.compressorTargetDb(1f, medium), 0.05f)
        assertEquals(0f, PcmDynamics.compressorTargetDb(1f, null), 0f)
    }

    @Test
    fun `strong turns full scale down about 6 dB and nothing below -7 dBFS down`() {
        val strong = PcmDynamics.presetFor(NightModeLevel.STRONG)
        assertEquals(-6f, PcmDynamics.compressorTargetDb(1f, strong), 0.5f)
        var db = -90f
        while (db <= -7f) {
            val change = PcmDynamics.compressorTargetDb(10f.pow(db / 20f), strong)
            assertTrue("at $db dB: $change", change >= -0.05f)
            db += 0.5f
        }
        assertEquals(15f, PcmDynamics.compressorTargetDb(10f.pow(-35f / 20f), strong), 0.05f)
    }

    @Test
    fun `light and medium turn nothing below full scale down`() {
        for (level in listOf(NightModeLevel.LIGHT, NightModeLevel.MEDIUM)) {
            val preset = PcmDynamics.presetFor(level)
            assertEquals("$level at full scale", 0f, PcmDynamics.compressorTargetDb(1f, preset), 0.05f)
            var db = -90f
            while (db <= 0f) {
                val change = PcmDynamics.compressorTargetDb(10f.pow(db / 20f), preset)
                assertTrue("$level at $db dB: $change", change >= -0.05f)
                db += 0.5f
            }
        }
    }
}
