package com.nuvio.tv.core.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomePosterLanesTest {
    @Test fun prefetchNeverTakesTheLanesOfPostersOnScreen() = runTest {
        val queue = ImagePriorityQueue(concurrency = 3, backgroundConcurrency = 1)
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { queue.run({ 5_000 }, { true }) { order += "prefetch 1"; release.await() } }
        launch { queue.run({ 5_001 }, { true }) { order += "prefetch 2"; release.await() } }
        launch { queue.run({ 0 }) { order += "visible 1"; release.await() } }
        launch { queue.run({ 1 }) { order += "visible 2"; release.await() } }
        runCurrent()
        assertEquals(listOf("prefetch 1", "visible 1", "visible 2"), order)
        release.complete(Unit)
        runCurrent()
        assertEquals("prefetch 2", order.last())
    }

    @Test fun aPosterOnScreenLiftsThePrefetchOfTheSamePoster() = runTest {
        val viewport = HomePosterViewport()
        val locks = PosterRequestLocks()
        val release = CompletableDeferred<Unit>()
        var seen: PosterWaiter? = null
        launch { locks.run("poster", HomePosterPriority(viewport, 3, prefetch = true)) { seen = it; release.await() } }
        runCurrent()
        val shared = seen!!
        assertTrue(shared.background)
        val prefetchRank = shared.rank()
        launch { locks.run("poster", HomePosterPriority(viewport, 0)) { } }
        runCurrent()
        assertFalse(shared.background)
        assertTrue(shared.rank() < prefetchRank)
        release.complete(Unit)
        runCurrent()
        assertEquals("released", locks.run("poster") { "released" })
    }

    @Test fun onlyPrefetchAndIdleWarmingCountAsBackground() {
        val viewport = HomePosterViewport()
        assertFalse(HomePosterPriority(viewport, 0).background)
        assertTrue(HomePosterPriority(viewport, 0, prefetch = true).background)
        assertTrue(HomePosterPriority(viewport, 0, progressive = true).background)
    }
}
