package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestMeterTest {

    @Test
    fun `a long idle gap inside one stream is stale once`() {
        var now = 1_000L
        val meter = RequestMeter { now }
        meter.started()
        now += 500
        meter.ended()
        now += 61_000
        assertTrue(meter.takeStale(60_000))
        assertFalse(meter.takeStale(60_000))
    }

    @Test
    fun `a new stream starts with no idle history from the last one`() {
        var now = 1_000L
        val meter = RequestMeter { now }
        meter.started()
        now += 500
        meter.ended()
        now += 5 * 60_000
        meter.reset()
        assertFalse(meter.takeStale(60_000))
        meter.started()
        now += 100
        meter.ended()
        assertFalse(meter.takeStale(60_000))
    }
}
