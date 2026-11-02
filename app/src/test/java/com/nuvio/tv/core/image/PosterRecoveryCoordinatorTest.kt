package com.nuvio.tv.core.image

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PosterRecoveryCoordinatorTest {
    private fun TestScope.waitFor(coordinator: PosterRecoveryCoordinator, key: String, rank: Int, granted: MutableList<String>): Job =
        launch {
            coordinator.awaitTurn(key) { rank }
            granted += key
        }

    @Test fun nothingRetriesWithoutATrigger() = runTest {
        val coordinator = PosterRecoveryCoordinator()
        val granted = mutableListOf<String>()
        launch { coordinator.run(ready = {}) }
        waitFor(coordinator, "a", 0, granted)
        advanceTimeBy(60_000)
        assertTrue(granted.isEmpty())
        assertEquals(1, coordinator.waiting)
        coroutineContext.cancelChildren()
    }

    @Test fun roundServesNearestRowsFirstStaggeredAndCappedPerPass() = runTest {
        val coordinator = PosterRecoveryCoordinator(perPass = 3, staggerMs = 150, settleMs = 1_000, passGapMs = 3_000, roundGapMs = 5_000)
        val granted = mutableListOf<String>()
        (0 until 5).forEach { waitFor(coordinator, "t$it", rank = 10 - it, granted = granted) }
        launch { coordinator.run(ready = {}) }
        runCurrent()
        coordinator.trigger()
        advanceTimeBy(999)
        assertTrue(granted.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf("t4"), granted)
        advanceTimeBy(150)
        assertEquals(listOf("t4", "t3"), granted)
        advanceTimeBy(150)
        assertEquals(listOf("t4", "t3", "t2"), granted)
        advanceTimeBy(2_000)
        assertEquals(3, granted.size)
        advanceTimeBy(1_000)
        assertEquals(listOf("t4", "t3", "t2", "t1"), granted)
        advanceUntilIdle()
        assertEquals(5, granted.size)
        coroutineContext.cancelChildren()
    }

    @Test fun aTileThatFailsAgainWaitsForTheNextRoundAndBurstsOfTriggersCollapse() = runTest {
        val coordinator = PosterRecoveryCoordinator(perPass = 24, staggerMs = 10, settleMs = 10, passGapMs = 10, roundGapMs = 100)
        var turns = 0
        launch {
            while (true) {
                coordinator.awaitTurn("same") { 0 }
                turns++
            }
        }
        launch { coordinator.run(ready = {}) }
        coordinator.trigger()
        advanceTimeBy(10_000)
        assertEquals(1, turns)
        repeat(5) { coordinator.trigger() }
        advanceTimeBy(10_000)
        assertEquals(3, turns)
        advanceTimeBy(60_000)
        assertEquals(3, turns)
        coroutineContext.cancelChildren()
    }

    @Test fun retriesWaitWhileHomeIsBusy() = runTest {
        val coordinator = PosterRecoveryCoordinator(staggerMs = 1_000, settleMs = 10)
        val granted = mutableListOf<String>()
        waitFor(coordinator, "a", 0, granted)
        waitFor(coordinator, "b", 1, granted)
        val viewport = HomePosterViewport()
        viewport.visibleRequests.incrementAndGet()
        launch { coordinator.run(ready = { awaitHomeIdle(viewport) }) }
        coordinator.trigger()
        advanceTimeBy(5_000)
        assertTrue(granted.isEmpty())
        viewport.visibleRequests.decrementAndGet()
        advanceTimeBy(150)
        assertEquals(listOf("a"), granted)
        viewport.scrolling = true
        advanceTimeBy(5_000)
        assertEquals(listOf("a"), granted)
        viewport.scrolling = false
        advanceTimeBy(150)
        assertEquals(listOf("a", "b"), granted)
        coroutineContext.cancelChildren()
    }

    @Test fun aTileThatLeavesTheScreenStopsWaiting() = runTest {
        val coordinator = PosterRecoveryCoordinator()
        val granted = mutableListOf<String>()
        val tile = waitFor(coordinator, "a", 0, granted)
        runCurrent()
        assertEquals(1, coordinator.waiting)
        tile.cancel()
        runCurrent()
        assertEquals(0, coordinator.waiting)
    }

    @Test fun aTileThatFailsLateAfterATriggerGetsOneMoreRound() = runTest {
        val coordinator = PosterRecoveryCoordinator(settleMs = 1_000, roundGapMs = 5_000, now = { testScheduler.currentTime })
        val granted = mutableListOf<String>()
        launch { coordinator.run(ready = {}) }
        coordinator.trigger()
        advanceTimeBy(26_000)
        assertEquals(0, coordinator.waiting)
        launch {
            while (true) {
                coordinator.awaitTurn("slow") { 0 }
                granted += "slow"
            }
        }
        advanceTimeBy(1_500)
        assertEquals(listOf("slow"), granted)
        advanceTimeBy(4_000)
        waitFor(coordinator, "second", 1, granted)
        advanceTimeBy(100_000)
        assertEquals(listOf("slow"), granted)
        coordinator.trigger()
        advanceTimeBy(1_500)
        assertEquals(setOf("slow", "second"), granted.toSet())
        assertEquals(3, granted.size)
        coroutineContext.cancelChildren()
    }

    @Test fun aFailureLongAfterTheTriggerWaitsForTheNextOne() = runTest {
        val coordinator = PosterRecoveryCoordinator(now = { testScheduler.currentTime })
        val granted = mutableListOf<String>()
        launch { coordinator.run(ready = {}) }
        coordinator.trigger()
        advanceTimeBy(61_000)
        waitFor(coordinator, "late", 0, granted)
        advanceTimeBy(100_000)
        assertTrue(granted.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test fun aFailureDuringTheRoundIsServedByThatRound() = runTest {
        val coordinator = PosterRecoveryCoordinator(settleMs = 1_000, passGapMs = 3_000, roundGapMs = 5_000, now = { testScheduler.currentTime })
        val granted = mutableListOf<String>()
        waitFor(coordinator, "a", 0, granted)
        launch { coordinator.run(ready = {}) }
        coordinator.trigger()
        advanceTimeBy(1_500)
        assertEquals(listOf("a"), granted)
        waitFor(coordinator, "b", 1, granted)
        advanceTimeBy(3_000)
        assertEquals(listOf("a", "b"), granted)
        waitFor(coordinator, "c", 2, granted)
        advanceTimeBy(100_000)
        assertEquals(listOf("a", "b", "c"), granted)
        waitFor(coordinator, "d", 3, granted)
        advanceTimeBy(100_000)
        assertEquals(listOf("a", "b", "c"), granted)
        coroutineContext.cancelChildren()
    }
}
