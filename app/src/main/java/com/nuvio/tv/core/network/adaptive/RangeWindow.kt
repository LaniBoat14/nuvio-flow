package com.nuvio.tv.core.network.adaptive

/** Bounded scheduling metadata; workers own payload memory separately. All operations are serialized. */
internal class RangeWindow(private val chunkBytes: Long, private val windowChunks: Int, private val maxRequests: Int = 6) {
    init { require(chunkBytes > 0 && windowChunks > 0 && maxRequests in 1..6) }
    data class Lease(val chunk: Long, val generation: Long, val attempt: Long, val hedge: Boolean)
    private var head = 0L
    private var endExclusive = Long.MAX_VALUE
    private var generation = 0L
    private var nextAttempt = 0L
    private val active = linkedMapOf<Long, Lease>()
    private val ready = mutableSetOf<Long>()
    private val hedged = mutableSetOf<Long>()
    @Synchronized fun seek(position: Long, length: Long): Set<Lease> {
        require(position >= 0 && length >= position)
        head = position/chunkBytes; endExclusive = length
        val removed = active.values.filter { !inWindow(it.chunk) }.toSet()
        removed.forEach { active.remove(it.attempt) }
        ready.removeAll { !inWindow(it) }; hedged.removeAll { !inWindow(it) }
        return removed
    }
    private fun inWindow(chunk: Long) = chunk >= head && chunk-head < windowChunks && chunk <= (endExclusive-1)/chunkBytes && endExclusive > 0
    @Synchronized fun acquire(): Lease? {
        if (active.size >= maxRequests) return null
        val chunk = (0 until windowChunks).asSequence().map { head+it }
            .firstOrNull { inWindow(it) && it !in ready && active.values.none { a -> a.chunk == it } } ?: return null
        return Lease(chunk, generation, ++nextAttempt, false).also { active[it.attempt] = it }
    }
    @Synchronized fun hedge(original: Lease): Lease? {
        if (active[original.attempt] != original || original.chunk in hedged || active.size >= maxRequests) return null
        // Hedges may account for no more than 25% of all in-flight requests, including this one.
        if ((active.values.count { it.hedge }+1)*4 > active.size+1) return null
        hedged.add(original.chunk)
        return Lease(original.chunk, generation, ++nextAttempt, true).also { active[it.attempt] = it }
    }
    /** First successful attempt wins; returns the attempts whose transport must be cancelled. */
    @Synchronized fun complete(lease: Lease): Set<Lease>? {
        if (active.remove(lease.attempt) != lease || !inWindow(lease.chunk) || lease.chunk in ready) return null
        ready.add(lease.chunk)
        return active.values.filter { it.chunk == lease.chunk }.toSet().also { losers -> losers.forEach { active.remove(it.attempt) } }
    }
    @Synchronized fun failed(lease: Lease) { active.remove(lease.attempt) }
    @Synchronized fun consume(): Long? {
        if (!ready.remove(head)) return null
        hedged.remove(head)
        return head++
    }
    @Synchronized fun reset() { generation++; active.clear(); ready.clear(); hedged.clear() }
}
