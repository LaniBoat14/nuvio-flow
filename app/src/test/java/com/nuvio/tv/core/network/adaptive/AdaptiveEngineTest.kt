package com.nuvio.tv.core.network.adaptive

import org.junit.Assert.*
import org.junit.Test

class AdaptiveEngineTest {
    @Test fun fixedReservationDoesNotOscillateAsOwnChunksAllocate() {
        val b=SessionRangeBudget()
        assertEquals(96,b.update(96,false))
        for(free in listOf(64,48,32,16,8,48,96)) assertEquals(96,b.update(free,false))
        assertEquals(48,b.update(8,true))
        assertEquals(48,b.update(4,true))
        b.reset()
        assertEquals(16,b.update(16,false))
    }
    @Test fun constrainedAutomaticCanUseTwoSmallerChunksInsteadOfStayingSingle() {
        val p=AdaptivePolicy(0)
        for(t in 2000L..20_000L step 2000) p.evaluate(t,80_000_000,2.0,30_000_000,16)
        assertEquals(2,p.mode.workers)
        assertEquals(8,p.mode.chunkMb)
    }
    @Test fun automaticEscalatesFromSavedSingleDuringPersistentBuffering() {
        val p=AdaptivePolicy(0)
        for(t in 2000L..80_000L step 2000) p.evaluate(t,80_000_000,2.0,30_000_000,96)
        assertEquals(4,p.mode.workers)
    }

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
        for (t in 2000L..54_000L step 2000) p.evaluate(t,80_000_000,40.0,250_000_000,128)
        assertEquals(2,p.index)
        p.evaluate(56_000,80_000_000,3.0,30_000_000,128)
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
    @Test fun modeComparisonUsesMedianRatherThanAnIsolatedPeak(){
        val samples=(0..4).associateWith{listOf(60L,60L,250L)}.toMutableMap()
        samples[3]=listOf(150L,150L,150L)
        val result=ModeComparison.select(samples,96)!!
        assertTrue(result.complete);assertEquals(3,result.index)
    }
    @Test fun modeComparisonRequestsMissingTrialsAndRespectsMemory(){
        assertEquals(2,ModeComparison.select(emptyMap(),96)!!.index)
        assertEquals(1,ModeComparison.select(emptyMap(),16)!!.index)
        assertNull(ModeComparison.select(emptyMap(),8))
    }

    @Test fun cumulativeHedgeBudgetIncludesDuplicateRetries(){
        val budget=HedgeBudget()
        repeat(3){budget.normalStarted()}
        assertTrue(budget.tryHedge())
        assertFalse(budget.tryHedge())
        repeat(3){budget.normalStarted()}
        assertTrue(budget.tryHedge())
        assertFalse(budget.tryHedge())
    }

}
