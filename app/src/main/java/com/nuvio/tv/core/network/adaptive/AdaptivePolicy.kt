package com.nuvio.tv.core.network.adaptive

/** Pure playback policy. Rates are bits/sec measured only while requests are reading. */
internal class AdaptivePolicy(initial: Int = 3, private val nowAtStart: Long = 0) {
    data class Mode(val workers: Int, val chunkMb: Int) {
        val reservedMb: Int get() = workers * chunkMb
    }
    data class Decision(val mode: Mode, val reason: String, val reprobe: Boolean = false)
    companion object {
        val modes = listOf(Mode(1,16), Mode(2,8), Mode(3,8), Mode(3,16), Mode(4,16))
        const val CHANGE_INTERVAL_MS = 20_000L
    }
    private data class Sample(val time: Long, val bps: Long)
    private val samples = ArrayDeque<Sample>()
    var index = initial.coerceIn(modes.indices)
        private set
    private var lastChange = nowAtStart
    private var healthySince: Long? = null
    private var lastProbe = nowAtStart
    private var latest = nowAtStart
    val mode get() = modes[index]

    fun selectObservedMode(selected: Int, now: Long): Decision {
        require(now >= latest)
        index=selected.coerceIn(modes.indices);lastChange=now;latest=now
        samples.clear();healthySince=null
        return Decision(mode,"Host profile re-evaluation")
    }

    fun evaluate(now: Long, bitrate: Long?, bufferSeconds: Double, activeBps: Long?,
                 memoryBudgetMb: Int, memoryPressure: Boolean = false): Decision {
        require(now >= latest) { "Monotonic time required" }
        latest = now
        if (activeBps != null && activeBps > 0) samples.addLast(Sample(now, activeBps))
        while (samples.isNotEmpty() && now - samples.first().time > 15_000) samples.removeFirst()
        val rates = samples.map { it.bps }.sorted()
        val median = rates.takeIf { it.size >= 3 }?.let { it[it.size/2] }
        val ceiling = modes.indices.lastOrNull { modes[it].reservedMb <= memoryBudgetMb } ?: 0
        fun change(next: Int, reason: String): Decision {
            index = next.coerceIn(0, ceiling); lastChange = now; healthySince = null
            samples.clear()
            return Decision(mode, reason)
        }
        if (index > ceiling) return change(ceiling, "Memory budget")
        if (memoryPressure) {
            if (now-lastChange >= 2_000 || index == 0) return change(index-1, "Memory pressure")
            return Decision(mode, "Memory pressure cooldown")
        }
        // No rate sample means the loader may be intentionally idle; never treat that as zero speed.
        val enough = bitrate?.takeIf { it > 0 }
        val atRisk = bufferSeconds < 10 || (enough != null && median != null && median < enough * 1.5)
        val healthy = activeBps != null && enough != null && median != null && median > enough * 2.5 && bufferSeconds >= 30
        healthySince = if (healthy) healthySince ?: now else null
        if (now-lastChange < CHANGE_INTERVAL_MS) return Decision(mode, "Holding mode")
        if (atRisk && index < ceiling) return change(index+1, "Buffer or delivery shortfall")
        if (atRisk && index == ceiling && now-lastProbe >= 60_000 && median != null) {
            lastProbe = now
            return Decision(mode, "Re-evaluate host profile", reprobe = true)
        }
        if (index > 0 && healthySince?.let { now-it >= 45_000 } == true) return change(index-1, "Sustained headroom")
        return Decision(mode, "Stable")
    }
}
