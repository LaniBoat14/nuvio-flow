package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.data.local.PlayerPreference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerPreferenceWaitTest {
    @Test
    fun `known preference is used without reading the setting`() = runTest {
        val source = flow<PlayerPreference> { error("should not be read") }

        assertEquals(PlayerPreference.EXTERNAL, awaitPlayerPreference(PlayerPreference.EXTERNAL, source))
    }

    @Test
    fun `missing preference waits for the setting instead of giving up`() = runTest {
        val source = MutableSharedFlow<PlayerPreference>()
        val waiter = async { awaitPlayerPreference(null, source) }

        runCurrent()
        assertFalse(waiter.isCompleted)

        source.emit(PlayerPreference.ASK_EVERY_TIME)
        runCurrent()

        assertTrue(waiter.isCompleted)
        assertEquals(PlayerPreference.ASK_EVERY_TIME, waiter.await())
    }
}
