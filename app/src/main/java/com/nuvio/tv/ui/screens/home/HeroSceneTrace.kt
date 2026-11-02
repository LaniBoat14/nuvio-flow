package com.nuvio.tv.ui.screens.home

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import coil3.imageLoader
import coil3.request.SuccessResult
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.ui.v2.diagnostics.UiFrameDiagnostics
import java.security.MessageDigest

/** Local test-build diagnostics. Never log titles, IDs, full URLs, query strings or tokens. */
internal object HeroSceneTrace {
    private val enabled = BuildConfig.VERSION_NAME.endsWith("-hero.1")
    fun now(): Long = SystemClock.elapsedRealtime()

    fun event(stage: String, owner: String?, detail: String = "") {
        if (enabled) Log.i("HeroSceneTrace", "t=${now()} stage=$stage owner=${fingerprint(owner)} $detail")
    }

    fun image(owner: String?, url: String, width: Int, height: Int, start: Long, result: SuccessResult?) {
        if (!enabled) return
        val image = result?.image
        event("decoded", owner, "asset=${fingerprint(url)} request=${width}x$height " +
            "decoded=${image?.width}x${image?.height} source=${result?.dataSource} elapsedMs=${now()-start}")
    }

    fun rendering(context: Context, owner: String?) {
        if (!enabled) return
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = manager?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
        val runtime = Runtime.getRuntime()
        // The preceding 500 ms is a bounded UI-thread sample, not GPU or network timing.
        val frames = UiFrameDiagnostics.frames.snapshot(System.nanoTime(), 500_000_000)
        event("render-window", owner, "windowMs=500 frames=${frames.frames} jank=${frames.jankyFrames} " +
            "p95UiMs=${frames.p95UiMs} p99UiMs=${frames.p99UiMs} lowMemory=${memory?.lowMemory} " +
            "heapBytes=${runtime.totalMemory()-runtime.freeMemory()} cacheBytes=${context.imageLoader.memoryCache?.size}")
    }

    private fun fingerprint(value: String?): String = value?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).take(6)
            .joinToString("") { byte -> "%02x".format(byte) }
    } ?: "none"
}
