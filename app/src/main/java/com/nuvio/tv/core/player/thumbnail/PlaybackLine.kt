package com.nuvio.tv.core.player.thumbnail

import android.os.SystemClock

/**
 * What playback's downloads need from the line now, published by Automatic speed-up while it runs. Thumbnails read it
 * to use only what the film does not need ([PlaybackHeadroom]).
 */
internal object PlaybackLine {
    /**
     * [bufferTargetMs] = the seconds of film this box's buffer may hold, 0 when unknown. [filmBps] = the film's rate in
     * bits per second (the busier of its average and its recent scenes), 0 when unknown. [deliveredBps] = what the
     * downloads carried while the player loaded over the last [DELIVERED_WINDOW_MS], 0 until that is known;
     * [loaderRested] = the loader rested for most of that time, so the line carried more than the film needed.
     * [requests] = playback requests running now, [needed] = the requests Automatic's plan says the film needs,
     * [serverLimit] = the most the film's server takes at once (its learned limit, TorBox's account total), and
     * [otherRequests] = playback requests on that server besides the playing stream's (a next episode starting early).
     * [filmKey] = the film server's [LedgerKey], the server the requests really read from once known (behind an add-on's
     * redirect), [linkKey] = the link's own [LedgerKey], [filmServer] = that server's HostSpeeds key (one name for all
     * its numbered nodes). [seekAtMs] = the user's last seek, [refusedAtMs] = the server's last "too many requests" to
     * playback, both on this clock. [spareBytes] = memory lent to pieces the film does not use
     * now (2 GB boxes only, else 0).
     */
    data class State(
        val atMs: Long,
        val bufferTargetMs: Long,
        val filmBps: Long,
        val deliveredBps: Long,
        val requests: Int,
        val loaderRested: Boolean = false,
        val needed: Int = requests,
        val serverLimit: Int = MOST_REQUESTS,
        val otherRequests: Int = 0,
        val filmKey: String? = null,
        val seekAtMs: Long = 0L,
        val refusedAtMs: Long = Long.MIN_VALUE / 2,
        val spareBytes: Long = 0L,
        val linkKey: String? = null,
        val filmServer: String? = null
    )

    const val FRESH_MS = 5_000L
    const val DELIVERED_WINDOW_MS = 25_000L
    const val MOST_REQUESTS = 8

    @Volatile
    private var state: State? = null

    fun publish(s: State) {
        state = s
    }

    fun clear() {
        state = null
    }

    /** The last state while Automatic still publishes, null otherwise. */
    fun now(nowMs: Long = SystemClock.elapsedRealtime()): State? =
        state?.takeIf { nowMs - it.atMs in 0..FRESH_MS }
}
