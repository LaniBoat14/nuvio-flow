package com.nuvio.tv.ui.components

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingIndicatorClockTest {

    @Test
    fun `frame runs from 0 to 60 every two seconds`() {
        assertEquals(0f, loadingIndicatorFrame(0L), 0f)
        assertEquals(30f, loadingIndicatorFrame(1_000L), 0.0001f)
        assertEquals(0f, loadingIndicatorFrame(LOADING_CYCLE_MS), 0f)
        assertEquals(15f, loadingIndicatorFrame(2_500L), 0.0001f)
        assertEquals(0f, loadingIndicatorFrame(-5L), 0f)
    }

    @Test
    fun `spinner keeps turning with animations off`() = runTest {
        val frames = BroadcastFrameClock()
        val off = object : MotionDurationScale {
            override val scaleFactor = 0f
        }
        val seen = mutableListOf<Float>()
        val spinner = launch(frames + off) { runLoadingIndicatorClock { seen += it } }
        runCurrent()
        frames.sendFrame(1_000_000_000L)
        runCurrent()

        frames.sendFrame(1_500_000_000L)
        runCurrent()
        assertEquals(15f, seen.last(), 0.0001f)

        frames.sendFrame(2_000_000_000L)
        runCurrent()
        assertEquals(30f, seen.last(), 0.0001f)

        spinner.cancel()
    }
}
