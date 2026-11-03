package com.nuvio.tv.core.player.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerThumbnailFormat
import com.nuvio.tv.data.mediaserver.ServerThumbnailSource
import com.nuvio.tv.data.mediaserver.ServerTrickplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Seek thumbnails the media server made itself (Jellyfin / Silo trickplay sheets, Emby BIF). Compressed sheets or
 * BIF blocks are kept in memory nearest the position first, one picture is cut out per request. Nothing is stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ServerThumbSet private constructor(
    private val source: ServerThumbnailSource,
    private val layout: Layout,
    private val chunks: Chunks,
    private val rgb565: Boolean,
    @Volatile private var wholePending: Boolean,
    private val playerProvider: () -> ExoPlayer?,
    private val onPicture: () -> Unit,
) {
    sealed class Layout {
        abstract val count: Int
        abstract val chunkCount: Int
        abstract val intervalMs: Long
        /** Width the pictures are served at. */
        abstract val servedWidth: Int
        abstract fun chunkOf(i: Int): Int
        abstract fun timeMs(i: Int): Long
        abstract fun indexFor(positionMs: Long): Int
        /** Length the set covers, compared with the player's duration. */
        abstract fun spanMs(): Long

        /** [listedAs]: the key the manifest listed this version under, sent as the sheets' mediaSourceId. */
        class Sheets(val level: ServerTrickplay, val listedAs: String) : Layout() {
            override val count = level.thumbnailCount
            override val chunkCount = TrickplayGrid.sheetCount(count, level.tileWidth, level.tileHeight)
            override val intervalMs = level.intervalMs
            override val servedWidth = level.width
            override fun chunkOf(i: Int) = TrickplayGrid.cell(i, level.tileWidth, level.tileHeight).sheet
            override fun timeMs(i: Int) = i * intervalMs
            override fun indexFor(positionMs: Long) = TrickplayGrid.indexFor(positionMs, intervalMs, count)
            override fun spanMs() = count * intervalMs
        }

        /** [ranged]: fetched in blocks by range request; else the whole file is chunk 0. */
        class Bif(val width: Int, val index: BifIndex, val ranged: Boolean) : Layout() {
            override val count = index.count
            override val chunkCount = if (ranged) (count + BIF_BLOCK - 1) / BIF_BLOCK else 1
            override val intervalMs = index.intervalMs()
            override val servedWidth = width
            override fun chunkOf(i: Int) = if (ranged) i / BIF_BLOCK else 0
            override fun timeMs(i: Int) = index.timeMs(i)
            override fun indexFor(positionMs: Long) = index.indexFor(positionMs)
            override fun spanMs() = index.timeMs(count - 1) + intervalMs
            fun chunkBase(c: Int): Long = if (ranged) index.start(c * BIF_BLOCK) else 0L
            fun chunkRange(c: Int): LongRange {
                val first = c * BIF_BLOCK
                val last = minOf(first + BIF_BLOCK, count) - 1
                return index.start(first) until index.end(last)
            }
        }
    }

    /** Compressed chunks in memory and their byte total; guarded by itself. */
    class Chunks {
        val map = ConcurrentHashMap<Int, ByteArray>()
        var bytes = 0L

        fun put(c: Int, data: ByteArray) {
            map.put(c, data)?.let { bytes -= it.size }
            bytes += data.size
        }
    }

    private class Retained(val key: String, val layout: Layout, val chunks: Chunks, val wholePending: Boolean)

    private val capBytes = compressedCapBytes()
    private val decodeWidth = decodeWidth()
    private val decoded = object : LruCache<Int, Bitmap>(
        ((if (MemoryGovernor.isStrongHardware) STRONG_DECODED_BYTES else SMALL_DECODED_BYTES) / 1024).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    /** Picture index to when it was last asked for; requests the pane stops repeating expire. */
    private val wanted = ConcurrentHashMap<Int, Long>()
    private val decoding = AtomicBoolean(false)
    /** Chunks the server refused (404, 403): never asked for again. Guarded by [chunks]. */
    private val missing = HashSet<Int>()
    /** Chunks that failed [MAX_TRIES] times: skipped until the next back-off. Guarded by [chunks]. */
    private val exhausted = HashSet<Int>()
    private val tries = HashMap<Int, Int>()
    private val regionDecoders = LinkedHashMap<Int, BitmapRegionDecoder>()
    private val crop = ThumbCrop.Estimator()
    @Volatile private var priorityMs = -1L
    @Volatile private var priorityAt = 0L
    @Volatile private var playbackMs = 0L
    @Volatile private var pictureWidth = (layout as? Layout.Sheets)?.level?.width ?: 0
    @Volatile private var pictureHeight = (layout as? Layout.Sheets)?.level?.height ?: 0
    @Volatile private var lastServed: Bitmap? = null
    @Volatile private var lastServedIndex = -1
    @Volatile private var stopped = false
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            try {
                run()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "fetching ended: ${t.javaClass.simpleName}")
            }
        }
        pump()
    }

    /** [keep]: a restart of the same file version may reuse what is in memory. */
    fun stop(keep: Boolean) {
        if (stopped) return
        stopped = true
        job?.cancel()
        scope.cancel()
        decodeScope.cancel()
        retained = if (keep) Retained(source.key, layout, chunks, wholePending) else null
        synchronized(regionDecoders) {
            regionDecoders.values.forEach { runCatching { it.recycle() } }
            regionDecoders.clear()
        }
        decoded.evictAll()
        lastServed = null
    }

    fun stripSpacingMs(): Long = layout.intervalMs

    fun frameAspect(): Float? {
        val w = pictureWidth
        val h = pictureHeight
        if (w <= 0 || h <= 0) return null
        val c = crop.current?.takeIf { it.width > 0 && it.height > 0 }
        val fw = if (c == null) 1f else (c.width - c.left - c.right).toFloat() / c.width
        val fh = if (c == null) 1f else (c.height - c.top - c.bottom).toFloat() / c.height
        return (w * fw) / (h * fh).coerceAtLeast(1f)
    }

    fun notePriority(positionMs: Long) {
        priorityMs = positionMs
        priorityAt = SystemClock.elapsedRealtime()
    }

    fun shownFor(positionMs: Long): SeekThumbnails.Shown? {
        val i = layout.indexFor(positionMs)
        if (i < 0) return null
        decoded.get(i)?.let { bmp ->
            lastServed = bmp
            lastServedIndex = i
            return SeekThumbnails.Shown(bmp, layout.timeMs(i), exact = true, crop = crop.current)
        }
        want(i)
        for (d in 1..NEIGHBOUR_PICTURES) {
            for (n in intArrayOf(i - d, i + d)) {
                val bmp = if (n in 0 until layout.count) decoded.get(n) else null
                if (bmp != null) return SeekThumbnails.Shown(bmp, layout.timeMs(n), exact = false, crop = crop.current)
            }
        }
        val prev = lastServed ?: return null
        return SeekThumbnails.Shown(prev, layout.timeMs(lastServedIndex.coerceAtLeast(0)), exact = false, crop = crop.current)
    }

    fun tileFor(positionMs: Long): SeekThumbnails.Shown? {
        val i = layout.indexFor(positionMs)
        if (i < 0) return null
        val bmp = decoded.get(i)
        if (bmp == null) {
            if (chunks.map.containsKey(layout.chunkOf(i))) want(i)
            return null
        }
        return SeekThumbnails.Shown(bmp, layout.timeMs(i), exact = true, crop = crop.current)
    }

    private fun focusIndex(): Int {
        val scrubbing = priorityMs >= 0 && SystemClock.elapsedRealtime() - priorityAt < SCRUB_FOCUS_MS
        return layout.indexFor(if (scrubbing) priorityMs else playbackMs).coerceAtLeast(0)
    }

    private suspend fun run() {
        var failuresInRow = 0
        var backOffRound = 0
        while (true) {
            val state = withContext(Dispatchers.Main) {
                playerProvider()?.let { p -> (p.playbackState == Player.STATE_BUFFERING) to p.currentPosition }
            }
            if (state == null) {
                delay(WAIT_MS)
                continue
            }
            playbackMs = state.second
            if (state.first || !SeekThumbnails.hostResumed) {
                delay(WAIT_MS)
                continue
            }
            val whole = wholePending
            val next = if (whole) 0 else synchronized(chunks) {
                val average = if (chunks.map.isEmpty()) 0L else chunks.bytes / chunks.map.size
                ServerThumbOrder.next(layout.chunkCount, layout.chunkOf(focusIndex()), chunks.map.keys, missing + exhausted,
                    chunks.bytes, average, capBytes)
            }
            if (next < 0) {
                delay(IDLE_MS)
                continue
            }
            val t0 = SystemClock.elapsedRealtime()
            val data = try {
                if (whole) withTimeoutOrNull(WHOLE_TIMEOUT_MS) { fetchWhole() }
                else withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetch(source, layout, next) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val failure = (e as? ServerException)?.failure
                Log.w(TAG, "chunk $next failed: ${failure ?: e.javaClass.simpleName}")
                if (!whole && (failure == ServerFailure.NOT_FOUND || failure == ServerFailure.FORBIDDEN)) {
                    synchronized(chunks) { missing += next }
                }
                null
            }
            if (data == null) {
                if (!whole) synchronized(chunks) {
                    val n = (tries[next] ?: 0) + 1
                    tries[next] = n
                    if (n >= MAX_TRIES) exhausted += next
                }
                if (++failuresInRow >= MAX_FAILURES) {
                    val wait = minOf(BACK_OFF_FIRST_MS shl backOffRound.coerceAtMost(4), BACK_OFF_MAX_MS)
                    Log.w(TAG, "$MAX_FAILURES failures in a row: next try in ${wait / 1000} s, the pictures in memory keep showing")
                    backOffRound++
                    failuresInRow = 0
                    synchronized(chunks) {
                        exhausted.clear()
                        tries.clear()
                    }
                    delay(wait)
                } else {
                    delay(RETRY_MS)
                }
                continue
            }
            failuresInRow = 0
            backOffRound = 0
            if (whole) wholePending = false
            val ms = SystemClock.elapsedRealtime() - t0
            synchronized(chunks) {
                chunks.put(next, data)
                while (chunks.bytes > capBytes) {
                    val drop = ServerThumbOrder.evict(chunks.map.keys, layout.chunkOf(focusIndex()), keep = next) ?: break
                    chunks.map.remove(drop)?.let { chunks.bytes -= it.size }
                }
            }
            Log.i(TAG, "chunk $next/${layout.chunkCount} ${data.size / 1024} KB in $ms ms " +
                "(memory ${chunks.bytes / 1024 / 1024} MB of ${capBytes / 1024 / 1024})")
            onPicture()
            pump()
        }
    }

    /** A BIF without range support: the whole file, cut at the memory cap. */
    private suspend fun fetchWhole(): ByteArray {
        val bif = layout as Layout.Bif
        return source.bif(bif.width, null, capBytes).bytes
    }

    private fun want(i: Int) {
        if (stopped || i !in 0 until layout.count) return
        if (decoded.get(i) != null) return
        wanted[i] = SystemClock.uptimeMillis()
        pump()
    }

    private fun pump() {
        if (stopped || !decoding.compareAndSet(false, true)) return
        decodeScope.launch {
            try {
                decodeWanted()
            } finally {
                decoding.set(false)
                if (!stopped && wanted.keys.any { chunks.map.containsKey(layout.chunkOf(it)) }) pump()
            }
        }
    }

    /** Nearest the focus first, but everything wanted from one sheet before the next, so its decoder is built once. */
    private fun decodeWanted() {
        var current = -1
        while (!stopped) {
            val now = SystemClock.uptimeMillis()
            wanted.entries.removeAll { now - it.value > WANTED_TTL_MS }
            val focus = focusIndex()
            val ready = wanted.keys.filter { chunks.map.containsKey(layout.chunkOf(it)) }
            if (ready.isEmpty()) return
            val i = ready.filter { layout.chunkOf(it) == current }.minByOrNull { abs(it - focus) }
                ?: ready.minByOrNull { abs(it - focus) } ?: return
            current = layout.chunkOf(i)
            wanted.remove(i)
            if (decoded.get(i) != null) continue
            val bmp = runCatching { decode(i) }
                .onFailure { Log.w(TAG, "picture $i not decoded: ${it.javaClass.simpleName}") }
                .getOrNull() ?: continue
            if (pictureWidth <= 0) {
                pictureWidth = bmp.width
                pictureHeight = bmp.height
            }
            measureBars(bmp)
            decoded.put(i, bmp)
            onPicture()
        }
    }

    private fun decode(i: Int): Bitmap? {
        val c = layout.chunkOf(i)
        val data = chunks.map[c] ?: return null
        val opts = decodeOptions(layout.servedWidth, decodeWidth, rgb565)
        return when (layout) {
            is Layout.Sheets -> {
                val level = layout.level
                val decoder = regionDecoder(c, data) ?: return null
                val r = TrickplayGrid.region(TrickplayGrid.cell(i, level.tileWidth, level.tileHeight), level.width, level.height)
                val rect = Rect(r[0], r[1], minOf(r[2], decoder.width), minOf(r[3], decoder.height))
                if (rect.width() <= 0 || rect.height() <= 0) return null
                decoder.decodeRegion(rect, opts)
            }
            is Layout.Bif -> {
                val base = layout.chunkBase(c)
                val from = layout.index.start(i) - base
                val to = layout.index.end(i) - base
                if (from < 0 || to > data.size || to <= from) return null
                BitmapFactory.decodeByteArray(data, from.toInt(), (to - from).toInt(), opts)
            }
        }
    }

    private fun regionDecoder(sheet: Int, data: ByteArray): BitmapRegionDecoder? = synchronized(regionDecoders) {
        if (stopped) return null
        regionDecoders.keys.filter { !chunks.map.containsKey(it) }.forEach { regionDecoders.remove(it)?.recycle() }
        regionDecoders.remove(sheet)?.let { d ->
            regionDecoders[sheet] = d
            return d
        }
        val d = newRegionDecoder(data) ?: return null
        regionDecoders[sheet] = d
        while (regionDecoders.size > MAX_REGION_DECODERS) {
            val oldest = regionDecoders.keys.first()
            regionDecoders.remove(oldest)?.recycle()
        }
        d
    }

    private fun measureBars(bmp: Bitmap) {
        if (crop.sampleCount >= ThumbCrop.Estimator.MAX_SAMPLES) return
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        runCatching { bmp.getPixels(px, 0, w, 0, 0, w, h) }.onFailure { return }
        crop.add(w, h, ThumbCrop.measure(w, h, px))
    }

    /** What [open] found: the layout and, when already in hand, the bytes of one chunk. */
    private class Found(val layout: Layout, val chunk: Int, val data: ByteArray?, val wholePending: Boolean)

    companion object {
        private const val TAG = "ThumbServer"
        private const val MB = 1024L * 1024L
        const val STRONG_CAP_BYTES = 24L * MB
        const val SMALL_CAP_BYTES = 8L * MB
        const val STRONG_DECODED_BYTES = 16L * MB
        const val SMALL_DECODED_BYTES = 6L * MB
        /** Decoded pictures stay at or below this width: about 30 fit the decoded cap, twice a full seek strip. */
        private const val STRONG_DECODE_WIDTH = 480
        private const val SMALL_DECODE_WIDTH = 320
        private const val SHEET_MAX_BYTES = 16L * MB
        /** BIF pictures per range request. */
        const val BIF_BLOCK = 30
        const val BIF_WIDTH = 320
        private const val BIF_PROBE_RANGE = 64L * 1024L
        private const val BIF_PROBE_MAX_BYTES = 256L * 1024L
        private const val NEIGHBOUR_PICTURES = 3
        private const val WANTED_TTL_MS = 3_000L
        private const val MAX_REGION_DECODERS = 2
        private const val SCRUB_FOCUS_MS = 4_000L
        private const val WAIT_MS = 500L
        private const val IDLE_MS = 1_000L
        private const val RETRY_MS = 2_000L
        private const val FETCH_TIMEOUT_MS = 20_000L
        private const val WHOLE_TIMEOUT_MS = 120_000L
        private const val MANIFEST_TIMEOUT_MS = 2_500L
        private const val MAX_TRIES = 2
        private const val MAX_FAILURES = 5
        private const val BACK_OFF_FIRST_MS = 30_000L
        private const val BACK_OFF_MAX_MS = 5L * 60 * 1000
        private const val PLAYER_WAIT_MS = 30_000L
        private const val SETTLE_WAIT_MS = 5_000L
        /** The set's length may differ from the player's duration by this share at most. */
        private const val MAX_LENGTH_MISMATCH = 0.10

        @Volatile private var retained: Retained? = null

        fun dropRetained() {
            retained = null
        }

        fun compressedCapBytes(): Long = if (MemoryGovernor.isStrongHardware) STRONG_CAP_BYTES else SMALL_CAP_BYTES

        fun decodeWidth(): Int = if (MemoryGovernor.isStrongHardware) STRONG_DECODE_WIDTH else SMALL_DECODE_WIDTH

        private fun decodeOptions(servedWidth: Int, decodeWidth: Int, rgb565: Boolean) = BitmapFactory.Options().apply {
            inSampleSize = TrickplayGrid.sampleSize(servedWidth, decodeWidth)
            inPreferredConfig = if (rgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }

        private fun newRegionDecoder(data: ByteArray): BitmapRegionDecoder? =
            if (Build.VERSION.SDK_INT >= 31) {
                BitmapRegionDecoder.newInstance(data, 0, data.size)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(data, 0, data.size, false)
            }

        /** The player's duration once known, then waits briefly for buffering to end. Null when it never got there. */
        private suspend fun awaitPlayback(playerProvider: () -> ExoPlayer?): Pair<Long, Long>? {
            val t0 = SystemClock.elapsedRealtime()
            var readyAt = 0L
            while (SystemClock.elapsedRealtime() - t0 < PLAYER_WAIT_MS) {
                val state = withContext(Dispatchers.Main) {
                    playerProvider()?.let { p ->
                        val duration = if (p.videoFormat != null && p.duration != C.TIME_UNSET) p.duration else 0L
                        Triple(duration, p.playbackState == Player.STATE_BUFFERING, p.currentPosition)
                    }
                }
                if (state != null && state.first > 0L) {
                    val now = SystemClock.elapsedRealtime()
                    if (readyAt == 0L) readyAt = now
                    if (!state.second || now - readyAt >= SETTLE_WAIT_MS) return state.first to state.third
                }
                delay(WAIT_MS)
            }
            return null
        }

        /**
         * The server's set for this file version, checked before it is used: the manifest (asked at once, short
         * timeout), its length against the player's, and one chunk at the play position that must download and
         * decode. Null on any doubt, so our own engine runs instead.
         */
        suspend fun open(
            source: ServerThumbnailSource,
            rgb565: Boolean,
            playerProvider: () -> ExoPlayer?,
            onPicture: () -> Unit,
        ): ServerThumbSet? {
            val kept = retained?.takeIf { it.key == source.key }
            retained = null
            if (kept != null) {
                Log.i(TAG, "${source.serverName}: same file version again, reusing ${kept.chunks.map.size} chunks in memory")
                return ServerThumbSet(source, kept.layout, kept.chunks, rgb565, kept.wholePending, playerProvider, onPicture)
            }
            val t0 = SystemClock.elapsedRealtime()
            val found = guarded(source, "manifest") {
                withTimeoutOrNull(MANIFEST_TIMEOUT_MS) {
                    when (source.format) {
                        ServerThumbnailFormat.TRICKPLAY -> readTrickplay(source)
                        ServerThumbnailFormat.BIF -> readBif(source)
                    }
                } ?: run {
                    Log.i(TAG, "${source.serverName}: no server thumbnails (none made, or no answer in time), own thumbnails")
                    null
                }
            } ?: return null
            Log.i(TAG, "${source.serverName}: manifest in ${SystemClock.elapsedRealtime() - t0} ms")
            val (durationMs, positionMs) = awaitPlayback(playerProvider) ?: run {
                Log.i(TAG, "${source.serverName}: player not ready in time, own thumbnails")
                return null
            }
            val layout = found.layout
            val span = layout.spanMs()
            if (abs(span - durationMs) > durationMs * MAX_LENGTH_MISMATCH) {
                Log.i(TAG, "${source.serverName}: set covers ${span / 1000} s, the file ${durationMs / 1000} s, own thumbnails")
                return null
            }
            val chunk = if (found.data != null) found.chunk else layout.chunkOf(layout.indexFor(positionMs).coerceAtLeast(0))
            val data = found.data ?: guarded(source, "first chunk") {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetch(source, layout, chunk) }
            } ?: return null
            if (!decodable(layout, chunk, data)) {
                Log.i(TAG, "${source.serverName}: chunk $chunk does not decode, own thumbnails")
                return null
            }
            val chunks = Chunks().apply { put(chunk, data) }
            Log.i(TAG, "${source.serverName}: server thumbnails in use, checked in ${SystemClock.elapsedRealtime() - t0} ms")
            return ServerThumbSet(source, layout, chunks, rgb565, found.wholePending, playerProvider, onPicture)
        }

        private suspend fun <T> guarded(source: ServerThumbnailSource, what: String, block: suspend () -> T?): T? =
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val failure = (e as? ServerException)?.failure
                Log.i(TAG, "${source.serverName}: $what failed (${failure ?: e.javaClass.simpleName}), own thumbnails")
                null
            }

        private suspend fun fetch(source: ServerThumbnailSource, layout: Layout, chunk: Int): ByteArray = when (layout) {
            is Layout.Sheets -> source.sheet(layout.listedAs, layout.level.width, chunk, SHEET_MAX_BYTES)
            is Layout.Bif -> {
                val range = layout.chunkRange(chunk)
                if (range.isEmpty()) throw ServerException(ServerFailure.NOT_FOUND, "empty block")
                val length = range.last - range.first + 1
                val reply = source.bif(layout.width, range, length + 1)
                if (!reply.partial || reply.bytes.size.toLong() != length) {
                    throw ServerException(ServerFailure.FAILED, "range not honoured")
                }
                reply.bytes
            }
        }

        /** The chunk's first picture reads as an image of the expected size. */
        private fun decodable(layout: Layout, chunk: Int, data: ByteArray): Boolean {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            return when (layout) {
                is Layout.Sheets -> {
                    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                    bounds.outWidth >= layout.level.width && bounds.outHeight >= layout.level.height
                }
                is Layout.Bif -> {
                    val first = if (layout.ranged) chunk * BIF_BLOCK else 0
                    val base = layout.chunkBase(chunk)
                    val from = layout.index.start(first) - base
                    val to = layout.index.end(first) - base
                    if (from < 0 || to > data.size || to <= from) return false
                    BitmapFactory.decodeByteArray(data, from.toInt(), (to - from).toInt(), bounds)
                    bounds.outWidth > 0 && bounds.outHeight > 0
                }
            }
        }

        private suspend fun readTrickplay(source: ServerThumbnailSource): Found? {
            val set = source.trickplay() ?: run {
                Log.i(TAG, "${source.serverName}: no trickplay made for this version, own thumbnails")
                return null
            }
            val widths = set.levels.map { it.width }
            val width = TrickplayGrid.chooseWidth(widths, decodeWidth()) ?: return null
            val level = set.levels.first { it.width == width }
            Log.i(TAG, "${source.serverName}: trickplay width $width of $widths, ${level.width}x${level.height}, " +
                "${level.tileWidth}x${level.tileHeight} per sheet, ${level.thumbnailCount} pictures every ${level.intervalMs} ms" +
                if (set.mediaSourceId != source.mediaSourceId) " (listed under ${set.mediaSourceId})" else "")
            return Found(Layout.Sheets(level, set.mediaSourceId), chunk = 0, data = null, wholePending = false)
        }

        private suspend fun readBif(source: ServerThumbnailSource): Found? {
            val probe = try {
                source.bif(BIF_WIDTH, 0L until BIF_PROBE_RANGE, BIF_PROBE_MAX_BYTES)
            } catch (e: ServerException) {
                if (e.failure == ServerFailure.NOT_FOUND) {
                    Log.i(TAG, "${source.serverName}: no BIF made for this version, own thumbnails")
                    return null
                }
                throw e
            }
            val count = BifIndex.headerCount(probe.bytes) ?: run {
                Log.i(TAG, "${source.serverName}: reply is not a BIF file, own thumbnails")
                return null
            }
            if (!probe.partial) {
                val fileLength = if (probe.truncated) null else probe.bytes.size.toLong()
                val index = BifIndex.parse(probe.bytes, fileLength = fileLength) ?: return null
                Log.i(TAG, "${source.serverName}: BIF without range support, $count pictures every ${index.intervalMs()} ms" +
                    if (probe.truncated) ", the whole file follows while playing" else "")
                return Found(Layout.Bif(BIF_WIDTH, index, ranged = false), chunk = 0, data = probe.bytes,
                    wholePending = probe.truncated)
            }
            var head = probe.bytes
            val need = BifIndex.indexBytes(count)
            if (need > head.size) {
                val rest = source.bif(BIF_WIDTH, head.size.toLong() until need, need - head.size + 1)
                if (!rest.partial || rest.bytes.size.toLong() != need - head.size) return null
                head += rest.bytes
            }
            val index = BifIndex.parse(head, fileLength = probe.totalLength) ?: return null
            Log.i(TAG, "${source.serverName}: BIF by range requests, $count pictures every ${index.intervalMs()} ms")
            return Found(Layout.Bif(BIF_WIDTH, index, ranged = true), chunk = 0, data = null, wholePending = false)
        }
    }
}
