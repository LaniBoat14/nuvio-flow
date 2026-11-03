package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.player.BitrateAwareLoadControl
import com.nuvio.tv.core.player.BufferSizing

/** Numbers the buffer settings show, worked out with the rules the player uses. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object BufferSettingsNumbers {
    /** A 4K remux at about 80 Mbit/s, the bitrate the back buffer is sized for. */
    const val REMUX_MB_PER_SECOND = 10

    /** Custom buffers without native memory never go past this budget. */
    val CUSTOM_ENGINE_MAX_MB: Int = (BitrateAwareLoadControl.MAX_BUDGET_BYTES / (1024L * 1024L)).toInt()

    private const val MB = 1024L * 1024L

    /** Android, the launcher and the app's own screens, decoders and video output, before any playback memory. */
    const val SYSTEM_SHARE_MB = 800
    private const val UNKNOWN_RAM_MB = 2048

    /** The line under Buffer ahead: a 4K remux at 90 Mbit/s and a web file at 15 Mbit/s. */
    const val REMUX_MBIT_PER_SECOND = 90
    const val WEB_FILE_MBIT_PER_SECOND = 15

    data class Reach(val mb: Int, val remuxSeconds: Int, val webFileSeconds: Int)

    /** Seconds [playbackMb] holds, rounded down. */
    fun reach(playbackMb: Int): Reach {
        val mb = playbackMb.coerceAtLeast(0)
        return Reach(mb, mb * 8 / REMUX_MBIT_PER_SECOND, mb * 8 / WEB_FILE_MBIT_PER_SECOND)
    }

    /** True when a 4K remux cannot fill [seconds] on this box: amber under Buffer ahead and the two start buffers. */
    fun remuxCannotFill(seconds: Int, reach: Reach): Boolean = seconds > reach.remuxSeconds

    data class BackBufferKept(val seconds: Int, val mb: Int)

    /**
     * The size the back buffer note works from: an automatic size is lowered at playback to what the box has free, so
     * once a film has played on the same engine, the size it really got.
     */
    fun playbackBufferMb(
        bufferMb: Int,
        automaticSize: Boolean,
        nativeMemory: Boolean,
        lastPlayback: BufferSizing.PlaybackBudget?
    ): Int {
        if (!automaticSize || lastPlayback == null) return bufferMb
        if (!lastPlayback.automatic || lastPlayback.nativeMemory != nativeMemory || lastPlayback.budgetMb <= 0) return bufferMb
        return lastPlayback.budgetMb
    }

    /**
     * The back buffer the player keeps inside [bufferMb]. With native memory the player also holds it to half of
     * [bufferAheadMs]. Null when nothing is kept.
     */
    fun backBufferKept(backBufferMs: Int, bufferMb: Int, nativeMemory: Boolean, bufferAheadMs: Int): BackBufferKept? {
        val setMs = if (nativeMemory) backBufferMs.coerceAtMost(bufferAheadMs / 2) else backBufferMs
        val keptMs = BufferSizing.backBufferMsForTarget(setMs, bufferMb.toLong() * MB)
        if (keptMs <= 0) return null
        return BackBufferKept(
            seconds = ((keptMs + 500) / 1000).coerceAtLeast(1),
            mb = ((keptMs.toLong() * REMUX_MB_PER_SECOND + 500) / 1000).toInt()
        )
    }

    /**
     * Above this a size the user set may stutter: the box's automatic size. Custom buffers without native memory
     * use their automatic size after the parallel chunks, on the slider's 25 MB grid.
     */
    fun stutterLimitMb(nativeMemory: Boolean, ramKnown: Boolean, autoSizeMb: Int, safeMaxMb: Int): Int = when {
        !ramKnown -> safeMaxMb
        nativeMemory -> autoSizeMb
        else -> (autoSizeMb / MemoryBudget.BUFFER_STEP_MB * MemoryBudget.BUFFER_STEP_MB)
            .coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
    }

    /** The buffer's own colour: a size the user set above the stutter or danger line. An automatic size fits itself. */
    fun bufferStatus(bufferMb: Int, automaticSize: Boolean, stutterLimitMb: Int, dangerMb: Int): MemoryUsageStatus = when {
        automaticSize -> MemoryUsageStatus.SAFE
        bufferMb > dangerMb -> MemoryUsageStatus.DANGER
        bufferMb > stutterLimitMb -> MemoryUsageStatus.WARNING
        else -> MemoryUsageStatus.SAFE
    }

    /**
     * The colour of the total the card shows (buffer + downloads + thumbnails), against what the box has beyond
     * [SYSTEM_SHARE_MB]: amber past half of it, red past 70 %. An automatic buffer counts at its shown size: it only
     * shrinks at playback when the rest crowds it out.
     */
    fun totalStatus(totalMb: Int, totalRamBytes: Long): MemoryUsageStatus {
        val ramMb = if (totalRamBytes > 0L) (totalRamBytes / MB).toInt() else UNKNOWN_RAM_MB
        val spareMb = (ramMb - SYSTEM_SHARE_MB).coerceAtLeast(ramMb / 4)
        return when {
            totalMb * 10L > spareMb * 7L -> MemoryUsageStatus.DANGER
            totalMb * 2L > spareMb -> MemoryUsageStatus.WARNING
            else -> MemoryUsageStatus.SAFE
        }
    }

    /** The memory card's colour: the worse of the buffer's own colour and the total's. */
    fun cardStatus(bufferStatus: MemoryUsageStatus, totalStatus: MemoryUsageStatus): MemoryUsageStatus =
        maxOf(bufferStatus, totalStatus)

    /** The amber line under Connection count: boxes with 2 GB or less, more than 2 connections. */
    fun connectionCountTooHigh(totalRamBytes: Long, connections: Int): Boolean =
        totalRamBytes > 0L && BufferSizing.isSmallBox(totalRamBytes) && connections > BufferSizing.SMALL_BOX_CONNECTIONS
}
