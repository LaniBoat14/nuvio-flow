package com.nuvio.tv.core.assessment

import com.nuvio.tv.core.network.StreamSweepEngine

/**
 * Buffer and network rules of the Device Assessment. The buffer is the app's own size for the box: Custom playback
 * buffers on so the buffer profile can set the times, Managed memory budget on, and no Target buffer size written.
 * Null from a change function means no change.
 */
internal object AssessmentBufferRules {
    private const val GB = 1024L * 1024L * 1024L
    private const val MAX_BUFFER_FLOOR_S = 10
    private const val MAX_BUFFER_CAP_S = 120

    /** Boxes sold as 4 GB and up; the same edge as the "4 GB" memory label. */
    val LARGE_BUFFER_RAM_BYTES: Long = (3.2 * GB).toLong()

    fun bufferEngineChange(enabled: Boolean): Boolean? = true.takeIf { !enabled }

    fun managedBudgetChange(managed: Boolean): Boolean? = true.takeIf { !managed }

    /** "Allow larger target buffer" is recommended off below 4 GB and left alone above. */
    fun allowLargeRecommendedOff(totalRamBytes: Long): Boolean = totalRamBytes < LARGE_BUFFER_RAM_BYTES

    fun allowLargeChange(totalRamBytes: Long, allowLarge: Boolean): Boolean? =
        false.takeIf { allowLarge && allowLargeRecommendedOff(totalRamBytes) }

    /** Seconds the automatic size holds at the title's bitrate, or null without a bitrate. */
    fun capacitySeconds(autoBufferMb: Int, bitrateMbps: Double?): Int? =
        bitrateMbps?.takeIf { it > 0 }?.let { (autoBufferMb * 8.0 / it).toInt() }

    fun recommendedMaxBufferSeconds(capacitySeconds: Int?): Int =
        (capacitySeconds ?: MAX_BUFFER_CAP_S).coerceIn(MAX_BUFFER_FLOOR_S, MAX_BUFFER_CAP_S)

    /**
     * Custom network: on when parallel connections are recommended, off when one connection is enough. With Automatic
     * speed-up on it stays off (Connections: Automatic), since a fixed number would stop the speed-up.
     */
    fun networkRecommendation(
        verdict: StreamSweepEngine.VerdictKind?,
        parallelRecommended: Boolean,
        autoSpeedup: Boolean
    ): Boolean? = when {
        autoSpeedup -> false
        verdict == StreamSweepEngine.VerdictKind.LEAVE_PARALLEL_OFF -> false
        parallelRecommended -> true
        else -> null
    }

    enum class ConnectionsGrounds { MORE_HELP, ONE_IS_ENOUGH, UNTESTED }

    /** Which speed test result explains the Connections: Automatic row. */
    fun connectionsGrounds(verdict: StreamSweepEngine.VerdictKind?, hasRecommendation: Boolean): ConnectionsGrounds =
        when {
            verdict == StreamSweepEngine.VerdictKind.LEAVE_PARALLEL_OFF -> ConnectionsGrounds.ONE_IS_ENOUGH
            verdict != null && verdict != StreamSweepEngine.VerdictKind.NONE && hasRecommendation ->
                ConnectionsGrounds.MORE_HELP
            else -> ConnectionsGrounds.UNTESTED
        }

    /** The sweep's summary line, kept only where it adds a warning the Connections row does not carry. */
    fun showSweepVerdict(verdict: StreamSweepEngine.VerdictKind?, autoSpeedup: Boolean): Boolean =
        !autoSpeedup ||
            verdict == StreamSweepEngine.VerdictKind.MARGINAL ||
            verdict == StreamSweepEngine.VerdictKind.CANNOT_SUSTAIN

    /** Parallel connections only count when Custom network is on; otherwise the stored ones are unused. */
    fun parallelInUse(customNetwork: Boolean, parallel: Boolean): Boolean = customNetwork && parallel

    fun networkChange(recommended: Boolean?, enabled: Boolean): Boolean? =
        recommended?.takeIf { it != enabled }

    enum class CurrentTarget { FINE, STUTTER, DANGER }

    /** How a Target buffer size the user set compares with the box's lines; Managed on means the app sizes it. */
    fun currentTarget(managed: Boolean, targetMb: Int, autoBufferMb: Int, dangerMb: Int): CurrentTarget = when {
        managed -> CurrentTarget.FINE
        targetMb > dangerMb -> CurrentTarget.DANGER
        targetMb > autoBufferMb -> CurrentTarget.STUTTER
        else -> CurrentTarget.FINE
    }
}
