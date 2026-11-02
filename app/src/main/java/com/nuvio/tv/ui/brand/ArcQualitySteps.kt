package com.nuvio.tv.ui.brand

import android.app.ActivityManager
import android.content.Context
import kotlin.math.min

/**
 * Decides when the entrance should draw less. Only a slow pace that lasts while our own drawing is the expensive
 * part counts: the start-up burst is waited out first, and each step gets a moment to show its effect, so a box
 * that really struggles steps down while the currents are still gathering.
 */
internal class ArcQualitySteps(startDetail: Int) {
    enum class Step { None, LessDetail }

    var detail = startDetail
        private set
    var drawAverageMs = 4f
        private set

    private var averageMs = 16.7f
    private var firstFrameNanos = 0L
    private var settleUntilNanos = 0L
    private var slowSinceNanos = 0L

    fun onDraw(spentMs: Float) {
        drawAverageMs = drawAverageMs * 0.9f + min(spentMs, 100f) * 0.1f
    }

    fun onFrame(intervalMs: Float, frameTimeNanos: Long): Step {
        if (firstFrameNanos == 0L) {
            firstFrameNanos = frameTimeNanos
            settleUntilNanos = frameTimeNanos + StartSettleNanos
        }
        averageMs = averageMs * 0.9f + min(intervalMs, 100f) * 0.1f
        val slow = frameTimeNanos >= settleUntilNanos && averageMs > SlowIntervalMs && drawAverageMs > SlowDrawMs
        if (!slow) {
            slowSinceNanos = 0L
            return Step.None
        }
        if (slowSinceNanos == 0L) slowSinceNanos = frameTimeNanos
        if (frameTimeNanos - slowSinceNanos < SustainNanos) return Step.None

        slowSinceNanos = 0L
        averageMs = 16.7f
        settleUntilNanos = frameTimeNanos + StepSettleNanos
        if (detail == 0) return Step.None
        detail--
        return Step.LessDetail
    }

    companion object {
        const val StartSettleNanos = 1_000_000_000L
        const val StepSettleNanos = 300_000_000L
        const val SustainNanos = 500_000_000L
        const val SlowIntervalMs = 23f
        const val SlowDrawMs = 11f
    }
}

/**
 * How a device runs the entrance, decided by its memory: boxes with under 3 GB (Fire TV Stick class) draw the light
 * version into a 1280x720 buffer from the first frame; the others draw full detail at 1080p and may only drop detail,
 * never the buffer size, so the mark and lettering stay sharp and never jump.
 */
internal data class ArcIntroTier(val detail: Int, val reducedBuffer: Boolean) {
    companion object {
        val Full = ArcIntroTier(2, false)
        val Light = ArcIntroTier(0, true)
        /** Boxes sold with 3 GB report about 2.6 to 2.9 GiB after the firmware's share, 2 GB boxes under 2 GiB. */
        private const val LowMemoryBytes = 2_560L * 1024 * 1024

        fun forMemory(totalMemoryBytes: Long): ArcIntroTier = if (totalMemoryBytes in 1 until LowMemoryBytes) Light else Full

        fun forDevice(context: Context): ArcIntroTier {
            val memory = ActivityManager.MemoryInfo()
            runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory) }
            return forMemory(memory.totalMem)
        }
    }
}
