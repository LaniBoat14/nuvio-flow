package com.nuvio.tv.ui.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AheadOfTimeTest {

    private class Manual {
        val queued = ArrayDeque<Runnable>()
        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        val ahead = AheadOfTime<String, String>({ queued.addLast(it) }, { ms, r -> scheduled += ms to r }, 30_000L)
        fun runAll() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    @Test fun preparedResultIsHandedOverOnce() {
        val m = Manual()
        assertTrue(m.ahead.warm("glass@1.0") { "scene" })
        m.runAll()
        assertEquals(AheadOfTime.Take.Hit("scene"), m.ahead.take("glass@1.0"))
        assertEquals(AheadOfTime.Take.Miss, m.ahead.take("glass@1.0"))
    }

    @Test fun sameKeyIsNotPreparedTwice() {
        val m = Manual()
        var made = 0
        assertTrue(m.ahead.warm("glass@1.0") { made++; "scene" })
        assertFalse(m.ahead.warm("glass@1.0") { made++; "scene" })
        m.runAll()
        assertEquals(1, made)
    }

    @Test fun otherSizeOrPaletteIsAMiss() {
        val m = Manual()
        m.ahead.warm("glass@1.0") { "scene" }
        m.runAll()
        assertEquals(AheadOfTime.Take.Miss, m.ahead.take("glass@0.6666667"))
        assertEquals(AheadOfTime.Take.Miss, m.ahead.take("gold@1.0"))
    }

    @Test fun paletteChangeDuringPrepareHandsOutOnlyTheNewPalette() {
        val m = Manual()
        m.ahead.warm("white@1.0") { "white" }
        // Membership settles while the first art is still being made.
        assertTrue(m.ahead.warm("gold@1.0") { "gold" })
        m.runAll()
        assertEquals(AheadOfTime.Take.Miss, m.ahead.take("white@1.0"))
        assertEquals(AheadOfTime.Take.Hit("gold"), m.ahead.take("gold@1.0"))
    }

    @Test fun paletteChangeBackWarmsAgain() {
        val m = Manual()
        m.ahead.warm("white@1.0") { "white" }
        m.ahead.warm("gold@1.0") { "gold" }
        assertTrue(m.ahead.warm("white@1.0") { "white again" })
        m.runAll()
        assertEquals(AheadOfTime.Take.Hit("white again"), m.ahead.take("white@1.0"))
    }

    @Test fun failedPrepareIsReportedAsFailedNotMiss() {
        val m = Manual()
        m.ahead.warm("glass@1.0") { throw OutOfMemoryError("art") }
        m.runAll()
        assertEquals(AheadOfTime.Take.Failed, m.ahead.take("glass@1.0"))
    }

    @Test fun resultNobodyTakesIsLetGo() {
        val m = Manual()
        m.ahead.warm("glass@1.0") { "scene" }
        m.runAll()
        assertEquals(30_000L, m.scheduled.single().first)
        m.scheduled.single().second.run()
        assertEquals(AheadOfTime.Take.Miss, m.ahead.take("glass@1.0"))
    }

    @Test fun lettingGoAnOldResultKeepsTheNewerOne() {
        val m = Manual()
        m.ahead.warm("white@1.0") { "white" }
        m.runAll()
        m.ahead.warm("gold@1.0") { "gold" }
        m.runAll()
        m.scheduled.first().second.run()
        assertEquals(AheadOfTime.Take.Hit("gold"), m.ahead.take("gold@1.0"))
    }

    @Test fun takeWaitsForWorkStillRunning() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val release = CountDownLatch(1)
            val ahead = AheadOfTime<String, String>({ pool.execute(it) }, { _, _ -> }, 30_000L)
            ahead.warm("glass@1.0") { release.await(5, TimeUnit.SECONDS); "scene" }
            val taker = Executors.newSingleThreadExecutor()
            val result = taker.submit<AheadOfTime.Take<String>> { ahead.take("glass@1.0") }
            Thread.sleep(50)
            assertFalse(result.isDone)
            release.countDown()
            assertEquals(AheadOfTime.Take.Hit("scene"), result.get(5, TimeUnit.SECONDS))
            taker.shutdown()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun replacedWorkThatHasNotStartedIsSkipped() {
        val m = Manual()
        var whiteMade = 0
        m.ahead.warm("white@1.0") { whiteMade++; "white" }
        m.ahead.warm("gold@1.0") { "gold" }
        m.runAll()
        assertEquals(0, whiteMade)
        assertEquals(AheadOfTime.Take.Hit("gold"), m.ahead.take("gold@1.0"))
    }

    @Test fun runningWorkIsToldOnceItIsReplaced() {
        val m = Manual()
        var staleBefore = true
        var staleAfter = false
        m.ahead.warm("white@1.0") { stale ->
            staleBefore = stale()
            m.ahead.warm("gold@1.0") { "gold" }
            staleAfter = stale()
            "white"
        }
        m.runAll()
        assertFalse(staleBefore)
        assertTrue(staleAfter)
        assertEquals(AheadOfTime.Take.Miss, m.ahead.take("white@1.0"))
        assertEquals(AheadOfTime.Take.Hit("gold"), m.ahead.take("gold@1.0"))
    }

    private class Objects {
        val scheduled = mutableListOf<Runnable>()
        val queued = ArrayDeque<Runnable>()
        val ahead = AheadOfTime<String, Any>({ queued.addLast(it) }, { _, r -> scheduled += r }, 30_000L)
        fun runAll() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(30) {
            if (ref.get() == null) return true
            System.gc()
            Thread.sleep(10)
        }
        return ref.get() == null
    }

    private fun takeAndDrop(o: Objects, key: String): WeakReference<Any> {
        val taken = o.ahead.take(key) as AheadOfTime.Take.Hit
        return WeakReference(taken.value)
    }

    @Test fun takenArtIsNotHeldUntilTheCleanup() {
        val o = Objects()
        o.ahead.warm("glass@1.0") { Any() }
        o.runAll()
        val ref = takeAndDrop(o, "glass@1.0")
        // The cleanup is still waiting to run; the art must already be free.
        assertEquals(1, o.scheduled.size)
        assertTrue(collected(ref))
    }

    @Test fun replacedThemesArtIsLetGoAtOnce() {
        val o = Objects()
        var whiteRef: WeakReference<Any>? = null
        o.ahead.warm("white@1.0") { Any().also { whiteRef = WeakReference(it) } }
        o.runAll()
        o.ahead.warm("gold@1.0") { Any() }
        o.runAll()
        assertEquals(2, o.scheduled.size)
        assertTrue(collected(whiteRef!!))
        assertTrue(o.ahead.take("gold@1.0") is AheadOfTime.Take.Hit)
    }
}
