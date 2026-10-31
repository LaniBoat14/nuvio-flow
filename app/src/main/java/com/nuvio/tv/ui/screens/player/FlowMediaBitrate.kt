package com.nuvio.tv.ui.screens.player

/** Windowed media rate: bytes per media duration, independent of download time. */
internal class FlowMediaBitrate {
    private var startUs = -1L
    private var lastUs = -1L
    private var bytes = 0L
    var bitsPerSecond: Long? = null
        private set

    fun reset() { startUs = -1; lastUs = -1; bytes = 0; bitsPerSecond = null }

    fun sample(timeUs: Long, size: Int) {
        if (timeUs < 0 || size <= 0 || timeUs == lastUs) return
        if (startUs < 0 || timeUs < lastUs || timeUs - lastUs > 5_000_000) {
            reset()
            startUs = timeUs
        }
        val duration = timeUs - startUs
        if (duration >= 3_000_000) {
            bitsPerSecond = bytes * 8_000_000L / duration
            startUs = timeUs
            bytes = 0
        }
        bytes += size
        lastUs = timeUs
    }
}

internal object FlowAudioTelemetry {
    val rate = FlowMediaBitrate()
    @Volatile var output: String? = null
    @Volatile var encodedBps: Long? = null
}
