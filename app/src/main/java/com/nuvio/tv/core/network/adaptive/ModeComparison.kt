package com.nuvio.tv.core.network.adaptive

/** Median comparisons over observed active-transfer windows; no peak-only selection. */
internal object ModeComparison {
    data class Selection(val index: Int, val complete: Boolean)
    fun select(observations: Map<Int,List<Long>>, memoryMb: Int): Selection? {
        val available=listOf(2,1,0,4,3).filter{AdaptivePolicy.modes[it].reservedMb<=memoryMb}
        if(available.isEmpty())return null
        val valid=observations.mapValues{(_,values)->values.filter{it>0}.sorted()}
        val challenger=available.firstOrNull{(valid[it]?.size ?: 0)<3}
        if(challenger!=null)return Selection(challenger,false)
        val best=available.maxByOrNull{index->valid.getValue(index).let{it[it.size/2]}} ?: return null
        return Selection(best,true)
    }
}
