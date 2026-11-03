package com.nuvio.tv.core.network.adaptive

import java.util.concurrent.atomic.AtomicLong

/** Duplicate attempts may never exceed 25% of all admitted range attempts. */
internal class HedgeBudget {
    private val normal=AtomicLong()
    private val hedges=AtomicLong()
    fun normalStarted(){normal.incrementAndGet()}
    fun tryHedge():Boolean {
        while(true) {
            val previous=hedges.get()
            if(3*(previous+1)>normal.get())return false
            if(hedges.compareAndSet(previous,previous+1))return true
        }
    }
    fun reset(){normal.set(0);hedges.set(0)}
}
