package com.nuvio.tv.core.network.adaptive

/** Session reservation, not a feedback loop based on heap left after our own allocations. */
internal class SessionRangeBudget {
    private var reserved: Int? = null
    private var pressureLatched = false
    fun reset() { reserved=null;pressureLatched=false }
    fun update(candidateMb:Int, pressure:Boolean):Int {
        if(reserved==null) reserved=candidateMb.coerceIn(0,96)
        if(pressure && !pressureLatched) reserved=(reserved!!/2).coerceAtLeast(8)
        pressureLatched=pressure
        return reserved!!
    }
}
