package com.nuvio.tv.ui.brand

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.util.StartupLatencyTrace
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Where the entrance is on its way to the screen; read from the main thread while the intro thread moves it on. */
enum class ArcIntroStage { Waiting, Preparing, Ready, Drawn, Failed }

/**
 * One piece of work done ahead of its use, keyed so a result made for something else is never handed out. A newer key
 * takes the place of an older one: the older work is skipped, or told it is no longer wanted, and its result is let go.
 * A result is held only until it is taken, or for [unusedMs] when nobody takes it.
 */
internal class AheadOfTime<K : Any, V : Any>(
    private val execute: (Runnable) -> Unit,
    private val schedule: (Long, Runnable) -> Unit,
    private val unusedMs: Long
) {
    sealed interface Take<out V> {
        data object Miss : Take<Nothing>
        data object Failed : Take<Nothing>
        data class Hit<V>(val value: V) : Take<V>
    }

    private class Slot<K, V>(val key: K) {
        val done = CountDownLatch(1)
        var value: V? = null
        var replaced = false
    }

    private val lock = Any()
    private var slot: Slot<K, V>? = null

    /**
     * Starts [make] for [key] unless that key is already done or on its way. False when nothing new was started.
     * [make] gets a check that turns true once its result is no longer wanted, so it can stop early.
     */
    fun warm(key: K, make: (stale: () -> Boolean) -> V?): Boolean {
        val s = synchronized(lock) {
            if (slot?.key == key) return false
            slot?.let { old -> old.replaced = true; old.value = null }
            Slot<K, V>(key).also { slot = it }
        }
        val stale = { synchronized(lock) { s.replaced } }
        execute(Runnable {
            try {
                if (stale()) return@Runnable
                val made = runCatching { make(stale) }.getOrNull()
                synchronized(lock) { if (!s.replaced) s.value = made }
            } finally {
                s.done.countDown()
            }
            schedule(unusedMs, Runnable {
                synchronized(lock) {
                    if (slot === s) slot = null
                    s.value = null
                }
            })
        })
        return true
    }

    /** Hands over the work for [key], waiting for it while it still runs. Blocking; call off the main thread. */
    fun take(key: K): Take<V> {
        val s = synchronized(lock) { slot?.takeIf { it.key == key }?.also { slot = null } } ?: return Take.Miss
        s.done.await()
        val v = synchronized(lock) { s.value.also { s.value = null } }
        return v?.let { Take.Hit(it) } ?: Take.Failed
    }
}

/** Prepares the entrance art as soon as the theme is known, so the ident does not wait for it. */
object ArcIntroPrep {
    private const val Tag = "ArcIntro"
    private const val UnusedMs = 30_000L
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { r -> Thread(r, "ArcIntroPrep") }
    private val ahead = AheadOfTime<String, ArcIgnitionScene>(worker::execute, { ms, r -> main.postDelayed(r, ms) }, UnusedMs)

    private fun key(palette: ArcPalette, pixelScale: Float) = "${palette.key}@$pixelScale"

    /** [width] x [height] is the size the ident will have, the activity's content; 0 falls back to the display. */
    fun warm(context: Context, palette: ArcPalette, width: Int, height: Int) {
        val app = context.applicationContext
        val metrics = app.resources.displayMetrics
        val w = if (width > 0) width else metrics.widthPixels
        val h = if (height > 0) height else metrics.heightPixels
        val pixelScale = ArcIgnitionView.bufferScaleFor(w, h, ArcIntroTier.forDevice(app).reducedBuffer)
        ahead.warm(key(palette, pixelScale)) { stale ->
            val begin = SystemClock.elapsedRealtime()
            StartupLatencyTrace.cold("intro_ahead_start", palette.key)
            runCatching { ArcIgnitionScene.prepare(app.resources, palette, pixelScale, keepGoing = { !stale() }) }
                .onFailure { if (!stale()) Log.w(Tag, "prepare ahead failed", it) }
                .getOrNull()
                .also {
                    val outcome = if (stale()) "dropped" else "ok=${it != null}"
                    StartupLatencyTrace.cold("intro_ahead_end", "${palette.key} ${SystemClock.elapsedRealtime() - begin} ms $outcome")
                }
        }
    }

    internal fun take(palette: ArcPalette, pixelScale: Float): AheadOfTime.Take<ArcIgnitionScene> =
        ahead.take(key(palette, pixelScale))
}
