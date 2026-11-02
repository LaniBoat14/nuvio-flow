package com.nuvio.tv.ui.screens.player

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlaybackThermalSamplerTest {

    private fun row(headroom: Float, limit: Float? = null) =
        thermalStatsRow(PlaybackThermalSampler.Reading(celsius = null, headroom = headroom, skinLimitC = limit))!!

    @Test fun chipReadingWinsAndKeepsOneDecimal() {
        val r = thermalStatsRow(PlaybackThermalSampler.Reading(celsius = 62.3f, headroom = 0.5f))!!
        assertEquals(CHIP_TEMP_LABEL, r.label)
        assertEquals(String.format("%.1f °C", 62.3f), r.value)
        assertEquals(StatsDot.GOOD, r.dot)
        assertEquals(StatsDot.BAD, thermalStatsRow(PlaybackThermalSampler.Reading(96f, null))!!.dot)
    }

    @Test fun knownLimitShowsEstimatedDegrees() {
        assertEquals("≈ 46 °C · limit 50 °C", row(0.86f, 50f).value)
        assertEquals(StatsDot.GOOD, row(0.86f, 50f).dot)
        assertEquals("≈ 48 °C · limit 50 °C", row(0.93f, 50f).value)
        assertEquals(StatsDot.WARN, row(0.93f, 50f).dot)
        assertEquals("≈ 50 °C · at limit", row(1.0f, 50f).value)
        assertEquals(StatsDot.BAD, row(1.0f, 50f).dot)
        assertEquals("≈ 52 °C · over 50 °C limit", row(1.07f, 50f).value)
        assertEquals("≤ 20 °C · limit 50 °C", row(0f, 50f).value)
        assertEquals(DEVICE_TEMP_LABEL, row(0.86f, 50f).label)
    }

    @Test fun unknownLimitShowsDistanceToIt() {
        assertEquals("12 °C below limit", row(0.6f).value)
        assertEquals(StatsDot.GOOD, row(0.6f).dot)
        assertEquals("at limit", row(0.995f).value)
        assertEquals(StatsDot.WARN, row(0.995f).dot)
        assertEquals("3 °C over limit", row(1.1f).value)
        assertEquals(StatsDot.BAD, row(1.1f).dot)
        assertEquals("30+ °C below limit", row(0f).value)
    }

    @Test fun nothingToShowWithoutAReading() {
        assertNull(thermalStatsRow(PlaybackThermalSampler.Reading(celsius = null, headroom = null)))
    }

    @Test fun fireStickPairsMatchTheFrameworkFormula() {
        // Headroom against the HAL SKIN value, measured on an AFTKRT.
        val pairs = listOf(
            0.76f to 42.8f, 0.78f to 43.7f, 0.80f to 44.6f, 0.84f to 45.6f, 0.86f to 46.4f,
            0.97f to 49.4f, 1.00f to 50.7f, 1.02f to 51.3f, 1.05f to 51.9f
        )
        val limit = PlaybackThermalSampler.SKIN_SEVERE_C_BY_MODEL.getValue("AFTKRT")
        val span = PlaybackThermalSampler.HEADROOM_SPAN_C
        pairs.forEach { (headroom, halC) ->
            val estimate = limit - span + span * headroom
            assertTrue("$headroom -> $estimate vs $halC", abs(estimate - halC) <= 1f)
        }
    }

    @Test fun lastHeadroomIsHeldAcrossMissedReads() {
        var now = 0L
        val sampler = PlaybackThermalSampler(mockk<Context>(relaxed = true), model = "AFTKRT", nowMs = { now })
        assertEquals(0.8f, sampler.heldHeadroom(0.8f))
        now = 5_000L
        assertEquals(0.8f, sampler.heldHeadroom(null))
        now = PlaybackThermalSampler.HEADROOM_HOLD_MS
        assertEquals(0.8f, sampler.heldHeadroom(null))
        now = PlaybackThermalSampler.HEADROOM_HOLD_MS + 1
        assertNull(sampler.heldHeadroom(null))
        assertEquals(0.9f, sampler.heldHeadroom(0.9f))
    }
}
