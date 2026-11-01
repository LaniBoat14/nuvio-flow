package com.nuvio.tv.core.network.adaptive

import org.junit.Assert.*
import org.junit.Test

class AdaptiveEngineTest {
    @Test fun deliveryDipScalesUpAfterCooldown() {
        val p = AdaptivePolicy()
        for (t in 2000L..20_000L step 2000) p.evaluate(t,80_000_000,5.0,60_000_000,128)
        assertEquals(4,p.mode.workers)
    }
    @Test fun idleFullBufferDoesNotCauseScaleUp() {
        val p = AdaptivePolicy()
        for (t in 2000L..60_000L step 2000) p.evaluate(t,80_000_000,50.0,null,128)
        assertEquals(3,p.mode.workers)
    }
    @Test fun memoryPressureOverridesNormalCooldown() {
        val p = AdaptivePolicy()
        p.evaluate(2000,80_000_000,5.0,60_000_000,128,true)
        assertEquals(2,p.index)
    }
    @Test fun stableHeadroomScalesDownWithoutFlapping() {
        val p = AdaptivePolicy()
        for (t in 2000L..50_000L step 2000) p.evaluate(t,80_000_000,40.0,250_000_000,128)
        assertEquals(2,p.index)
        p.evaluate(52_000,80_000_000,3.0,30_000_000,128)
        assertEquals(2,p.index)
    }
    @Test fun outOfOrderCompletionIsConsumedInOrder() {
        val s = RangeWindow(16,4)
        s.seek(0,100)
        val a=s.acquire()!!;val b=s.acquire()!!
        s.complete(b);assertNull(s.consume())
        s.complete(a);assertEquals(0L,s.consume());assertEquals(1L,s.consume())
    }
    @Test fun seekingCancelsOnlyOutsideWindowAndRejectsStaleCompletion() {
        val s=RangeWindow(16,3)
        s.seek(0,200)
        val a=s.acquire()!!;val b=s.acquire()!!
        val cancelled=s.seek(16,200)
        assertTrue(a in cancelled);assertFalse(b in cancelled)
        assertNull(s.complete(a));assertNotNull(s.complete(b));assertEquals(1L,s.consume())
    }
    @Test fun hedgeBudgetAndFirstWinnerAreEnforced() {
        val s=RangeWindow(16,6)
        s.seek(0,200)
        val a=s.acquire()!!;s.acquire();s.acquire()
        val hedge=s.hedge(a)!!
        assertNull(s.hedge(a))
        assertEquals(setOf(a),s.complete(hedge))
        assertNull(s.complete(a));assertEquals(0L,s.consume())
    }
}
