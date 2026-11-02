package com.nuvio.tv.core.image

import coil3.network.HttpException
import coil3.network.NetworkResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MissingPosterMemoryTest {
    private fun http(code: Int) = HttpException(NetworkResponse(code = code))

    @Test fun onlyNotFoundAndGoneAreRemembered() {
        val missing = MissingPosterMemory()
        missing.record("a", http(404))
        missing.record("b", http(410))
        missing.record("c", http(503))
        missing.record("d", http(403))
        missing.record("e", java.net.SocketTimeoutException())
        assertTrue("a" in missing)
        assertTrue("b" in missing)
        assertFalse("c" in missing)
        assertFalse("d" in missing)
        assertFalse("e" in missing)
    }

    @Test fun memoryIsBounded() {
        val missing = MissingPosterMemory(cap = 2)
        missing.record("a", http(404))
        missing.record("b", http(404))
        missing.record("c", http(404))
        assertFalse("a" in missing)
        assertTrue("b" in missing)
        assertTrue("c" in missing)
    }

    @Test fun aPosterTheServerDoesNotHaveIsNotRetriedInLaterRounds() = runTest {
        val coordinator = PosterRecoveryCoordinator(settleMs = 10, passGapMs = 10, roundGapMs = 10)
        val missing = MissingPosterMemory()
        var loads = 0
        launch { coordinator.run(ready = {}) }
        launch {
            recoverPoster("row:a", "a_229x343_v0", { 0 }, coordinator, missing) {
                loads++
                missing.record("a_229x343_v0", http(404))
                false
            }
        }
        coordinator.trigger()
        advanceTimeBy(1_000)
        assertEquals(1, loads)
        repeat(3) {
            coordinator.trigger()
            advanceTimeBy(1_000)
        }
        assertEquals(1, loads)
        assertEquals(0, coordinator.waiting)
        coroutineContext.cancelChildren()
    }

    @Test fun aChangedAddressOrABusyServerStillGetsRetried() = runTest {
        val coordinator = PosterRecoveryCoordinator(settleMs = 10, passGapMs = 10, roundGapMs = 10)
        val missing = MissingPosterMemory()
        missing.record("old_229x343_v0", http(404))
        var loads = 0
        launch { coordinator.run(ready = {}) }
        launch {
            recoverPoster("row:new", "new_229x343_v0", { 0 }, coordinator, missing) {
                loads++
                missing.record("new_229x343_v0", http(503))
                loads == 3
            }
        }
        repeat(3) {
            coordinator.trigger()
            advanceTimeBy(1_000)
        }
        assertEquals(3, loads)
        assertEquals(0, coordinator.waiting)
        coroutineContext.cancelChildren()
    }
}
