package com.nuvio.tv.core.player

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * On small boxes the screen drawing engine keeps tens of MB of spare drawing surfaces from the menus. A film needs
 * none of them, so they are handed back when a film starts, the same release Android does when the app goes to the
 * background. The menus redraw them when they come back.
 */
object DrawingMemory {
    private const val TAG = "DrawingMemory"
    private const val MEASURE_AFTER_MS = 1_500L

    private val main = Handler(Looper.getMainLooper())

    fun appliesTo(totalMem: Long): Boolean = BufferSizing.isSmallBox(totalMem)

    fun releaseForFilm(context: Context, totalMem: Long, reason: String) {
        if (!appliesTo(totalMem)) return
        val app = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) report(trimDrawingCaches(), reason, app)
        else main.post { report(trimDrawingCaches(), reason, app) }
    }

    private fun report(released: Boolean, reason: String, app: Context) {
        Thread({
            Thread.sleep(MEASURE_AFTER_MS)
            Log.i(TAG, "DRAW_MEMORY: released=$released reason=$reason graphicsMb=${graphicsMb()} " +
                "availMb=${BufferSizing.read(app)?.availMb ?: -1}")
        }, "drawing-memory").apply { isDaemon = true }.start()
    }

    private fun trimDrawingCaches(): Boolean = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        global.getMethod("trimMemory", Int::class.javaPrimitiveType)
            .invoke(instance, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        true
    }.getOrElse {
        Log.w(TAG, "DRAW_MEMORY: release not available: ${it.javaClass.simpleName}")
        false
    }

    private fun graphicsMb(): Int = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        (info.getMemoryStat("summary.graphics")?.toIntOrNull() ?: -1024) / 1024
    }.getOrDefault(-1)
}
