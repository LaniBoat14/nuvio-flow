package com.nuvio.tv.core.player.thumbnail

import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Keyframe reads in batches: a read at a planned keyframe fetches it with the next planned ones in one multi-range
 * request (one connection, one wait), and later reads are served from those bytes. A server that does not answer with
 * its parts is remembered in [MultiRangeSupport] and gets single reads, as does anything not planned. Safe for reads
 * from several threads; at most [budgetBytes] held or on the way.
 */
internal class BatchRangeReader(
    private val inner: HttpRangeReader,
    private val support: MultiRangeSupport,
    private val budgetBytes: Long,
) : RangeReader {
    companion object {
        const val TAG = "ThumbWorker"
        const val PIECES = 20
        const val MIN_PIECES = 3
        /** Matroska plans cluster + CueRelativePosition; the block starts a cluster header (under 16 bytes) further. */
        const val MKV_LEAD = 32
        const val MIN_WINDOW = 256 * 1024
        /** A held piece shorter than this from the read's start is not worth a short read. */
        const val MIN_SERVE = 64 * 1024
        const val WAIT_MS = 30_000L
        const val SMALL_BUDGET = 16L * 1024 * 1024
        const val STRONG_BUDGET = 48L * 1024 * 1024

        /** Planned pieces, sorted, with overlapping or touching ones joined: the ranges one request asks for. */
        fun ranges(pieces: List<LongRange>): List<LongRange> {
            val out = ArrayList<LongRange>()
            for (p in pieces.sortedBy { it.first }) {
                val last = out.lastOrNull()
                if (last != null && p.first <= last.last) out[out.size - 1] = last.first..maxOf(last.last, p.last)
                else out += p
            }
            return out
        }
    }

    /** A keyframe to come: [length] <= 0 = the extractor's current window (Matroska), else its exact size. */
    class Planned(val start: Long, val length: Int)

    private class Piece(val start: Long, val bytes: ByteArray) {
        val end: Long get() = start + bytes.size
        var used = false
    }

    private class Flight(val start: Long, val end: Long, val done: CompletableFuture<Unit>)

    private val lock = Any()
    private var plan: List<Planned> = emptyList()
    private val held = ArrayList<Piece>()
    private var heldBytes = 0L
    private val flights = ArrayList<Flight>()
    private var flightBytes = 0L
    @Volatile private var window = MIN_WINDOW
    @Volatile private var off = false
    @Volatile private var firstLogged = false

    /** The server answered a batch with something other than its parts: single reads only. */
    val refused: Boolean get() = off

    @Volatile var batches = 0
        private set
    @Volatile var served = 0
        private set

    override val totalLength: Long get() = inner.totalLength

    /** Worker: the keyframes coming next, in fetch order; empty = single reads (during playback). */
    fun plan(next: List<Planned>) = synchronized(lock) {
        plan = next
        evict(0)
    }

    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        val n = readInto(offset, length, ByteBuffer.wrap(out))
        return if (n == length) out else out.copyOf(n)
    }

    override fun readInto(offset: Long, length: Int, dst: ByteBuffer): Int {
        if (length >= MIN_WINDOW) window = maxOf(window - window / 8, length)
        if (off || !support.allowed(inner.resolvedUrl)) return inner.readInto(offset, length, dst)
        serve(offset, length, dst)?.let { return it }
        var wait: CompletableFuture<Unit>? = null
        var batch: List<LongRange>? = null
        var done: CompletableFuture<Unit>? = null
        synchronized(lock) {
            val f = flights.firstOrNull { offset >= it.start && offset < it.end }
            if (f != null) wait = f.done else {
                val b = formBatch(offset)
                if (b != null) {
                    done = CompletableFuture<Unit>()
                    for (r in b) flights += Flight(r.first, r.last, done!!)
                    flightBytes += b.sumOf { it.last - it.first }
                    batch = b
                }
            }
        }
        wait?.let {
            runCatching { it.get(WAIT_MS, TimeUnit.MILLISECONDS) }
            return serve(offset, length, dst) ?: inner.readInto(offset, length, dst)
        }
        val b = batch ?: return inner.readInto(offset, length, dst)
        fetch(b, done!!)
        return serve(offset, length, dst) ?: inner.readInto(offset, length, dst)
    }

    private fun pieceLength(p: Planned): Int = if (p.length > 0) p.length else window + MKV_LEAD

    /** Under [lock]: the planned pieces from the one at [offset] on, not held or on the way, within the budget. */
    private fun formBatch(offset: Long): List<LongRange>? {
        val i = plan.indexOfFirst { offset >= it.start && offset <= it.start + MKV_LEAD }
        if (i < 0) return null
        val per = pieceLength(plan[i]).toLong()
        val room = budgetBytes - heldBytes - flightBytes
        val most = minOf(PIECES.toLong(), room / per).toInt()
        if (most < MIN_PIECES) return null
        val out = ArrayList<LongRange>()
        for (k in i until plan.size) {
            if (out.size >= most) break
            val p = plan[k]
            val r = p.start..(p.start + pieceLength(p))
            if (held.any { r.first >= it.start && r.first < it.end }) continue
            if (flights.any { r.first < it.end && r.last > it.start }) continue
            out += r
        }
        return if (out.size >= MIN_PIECES) ranges(out) else null
    }

    private fun fetch(batch: List<LongRange>, done: CompletableFuture<Unit>) {
        val url = inner.resolvedUrl
        val asked = batch.sumOf { it.last - it.first }
        val t0 = SystemClock.elapsedRealtime()
        try {
            val parts = inner.readRanges(batch, asked + MKV_LEAD)
            support.note(url, true)
            synchronized(lock) {
                evict(parts.sumOf { it.second.size.toLong() })
                for ((start, bytes) in parts) {
                    held += Piece(start, bytes)
                    heldBytes += bytes.size
                }
            }
            batches++
            if (!firstLogged) {
                firstLogged = true
                Log.i(TAG, "multi-range: ${batch.size} keyframes in one request (${asked / 1024} KB, " +
                    "${SystemClock.elapsedRealtime() - t0} ms, ${parts.size} parts) - batches on this server")
            }
        } catch (e: HttpRangeReader.MultiRangeRefused) {
            off = true
            support.note(url, false)
            Log.i(TAG, "multi-range: ${e.message} - single reads on this server")
        } catch (e: IOException) {
            // A rate limit or dropped connection is the caller's to handle, as for a single read.
            if (e is RateLimitedException) throw e
            Log.i(TAG, "multi-range request failed (${e.javaClass.simpleName}) - single reads for these")
        } finally {
            synchronized(lock) {
                flights.removeAll { it.done === done }
                flightBytes -= asked
            }
            done.complete(Unit)
        }
    }

    /** Under no lock: the bytes at [offset] from a held piece, or null. */
    private fun serve(offset: Long, length: Int, dst: ByteBuffer): Int? = synchronized(lock) {
        val p = held.firstOrNull { offset >= it.start && offset < it.end } ?: return null
        val avail = (p.end - offset).toInt()
        val n = minOf(length, avail, dst.remaining())
        if (n < length && avail < MIN_SERVE) return null
        dst.put(p.bytes, (offset - p.start).toInt(), n)
        p.used = true
        served++
        n
    }

    /** Under [lock]: pieces the plan has left go; then, for [need] more bytes, read pieces first, then the oldest. */
    private fun evict(need: Long) {
        val planned = plan.map { it.start }
        val left = held.filter { p -> planned.none { it >= p.start - MKV_LEAD && it < p.end } }
        for (p in left) drop(p)
        for (p in held.filter { it.used } + held.filter { !it.used }) {
            if (heldBytes + flightBytes + need <= budgetBytes) break
            drop(p)
        }
    }

    private fun drop(p: Piece) {
        if (held.remove(p)) heldBytes -= p.bytes.size
    }
}
