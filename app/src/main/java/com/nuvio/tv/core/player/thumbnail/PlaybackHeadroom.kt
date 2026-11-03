package com.nuvio.tv.core.player.thumbnail

import com.nuvio.tv.ui.screens.player.HostSpeeds

/**
 * Thumbnail fetch lanes while the film plays. Paused or buffered to the end: no limit from here.
 *
 * While Automatic speed-up publishes the line ([PlaybackLine]), thumbnails use only what the film does not need (the
 * surplus). Lanes run while all of these hold: the downloads carry at least [SURPLUS_RATE_PERCENT] % of the film's
 * rate (or the loader rests, so they carry more), the buffer has not fallen over [TREND_WINDOW_MS] while loading and
 * holds at least [FLOOR_MS], and the server has requests left. On the film's server that is its limit less what
 * playback runs or its plan needs, whichever is more, and less other playback requests (TorBox: one account total). A
 * copy on another server uses that server's own limit; the film's own file, and a copy on the server the film really
 * reads from (behind an add-on's redirect, any of its numbered nodes), count against the film's. None at once on
 * buffering, a seek, a "too many requests", the buffer falling, or when playback needs a request back: these stop the
 * reads under way ([gaveWay]). A rate under the margin stops new reads only. Less room takes a lane away only after
 * the room has been short for [SHRINK_AFTER_MS], and the reads under way finish; after that each lane back waits for
 * [GOOD_FOR_MS] of room. Lanes come back one at a time only after [GOOD_FOR_MS] of good conditions.
 *
 * Without Automatic: none while it buffers, when the seconds ahead fall below [LOW_AHEAD_MS], or when they fall
 * [FAST_FALL_MS] within [FALL_WINDOW_MS] while playback is loading; then one more every [STEP_UP_MS] once
 * [BACK_AHEAD_MS] ahead again.
 */
internal class PlaybackHeadroom(
    private val max: Int = ThumbFetchLanes.MAX,
    private val line: (Long) -> PlaybackLine.State? = { PlaybackLine.now(it) },
    private val refused: (String?) -> Boolean = { ConnectionLedger.app.refusalQuietMs(it) > 0L },
    private val hostRoom: (String) -> Int = ::otherHostRoom
) {
    companion object {
        const val LOW_AHEAD_MS = 6_000L
        const val BACK_MARGIN_MS = 2_000L
        const val BACK_AHEAD_MS = LOW_AHEAD_MS + BACK_MARGIN_MS
        const val FALL_WINDOW_MS = 6_000L
        const val FAST_FALL_MS = 4_000L
        const val STEP_UP_MS = 3_000L

        const val SURPLUS_RATE_PERCENT = 130
        const val TREND_WINDOW_MS = 10_000L
        const val FALL_TOLERANCE_MS = 1_000L
        const val FLOOR_MS = 3_000L
        const val GOOD_FOR_MS = 10_000L
        const val REFUSAL_HOLD_MS = 15_000L
        const val SHRINK_AFTER_MS = 5_000L

        /** Fetched pictures waiting for the decoder while playing; more fetches would only wait with them. */
        const val DECODE_AHEAD = 2

        /** A strip decode's memory ([DecodeTier.SW_4K_STRIPS]). */
        const val STRIP_BYTES = 25L * 1024 * 1024
        private const val NONE = Long.MIN_VALUE

        /**
         * Requests left for thumbnails on the film's server: its limit less playback's running requests or the ones
         * its plan needs, whichever is more, and less other playback requests.
         */
        fun filmRoom(s: PlaybackLine.State): Int =
            s.serverLimit - maxOf(s.requests, s.needed) - s.otherRequests

        /** The downloads carry the film with margin, or the loader rests because they carried more. */
        fun rateCarries(s: PlaybackLine.State): Boolean =
            s.loaderRested || (s.filmBps > 0L && s.deliveredBps * 100 >= s.filmBps * SURPLUS_RATE_PERCENT)

        /** A strip decode may run while playing: playback's lent but unused pieces cover its memory. */
        fun stripFits(s: PlaybackLine.State?): Boolean = s != null && s.spareBytes >= STRIP_BYTES

        /** [url] reads from the film's server: its ledger key, the link's, or the same server under another node. */
        fun onFilmServer(url: String?, s: PlaybackLine.State): Boolean {
            val key = LedgerKey.of(url) ?: return true
            if (s.filmKey == null || key == s.filmKey || key == s.linkKey) return true
            return s.filmServer != null && HostSpeeds.keyOf(url) == s.filmServer
        }

        fun seconds(ms: Long): String = ms.coerceAtLeast(0L).let { "${it / 1000}.${it % 1000 / 100} s" }

        private fun mbit(bps: Long): String = "${bps / 1_000_000}"

        /** Requests a server other than the film's leaves thumbnails: its known or learned limit, less playback's there. */
        fun otherHostRoom(url: String): Int {
            val key = LedgerKey.of(url)
            val limit = LedgerKey.knownTotal(key) ?: HostSpeeds.keyOf(url)?.let { HostSpeeds.limitOf(it) }
                ?: PlaybackLine.MOST_REQUESTS
            return minOf(limit, PlaybackLine.MOST_REQUESTS) - ConnectionLedger.app.playbackInUse(url)
        }
    }

    var lanes = max
        private set

    /** For the log: what the last sample said. */
    var why = "buffer not seen yet"
        private set

    /** The last sample was judged on the surplus (Automatic publishes the line). */
    var surplus = false
        private set

    /** Counts the hard stops while playing (seek, 429, stall, falling buffer, no room); reads under way are cancelled. */
    var gaveWay = 0
        private set

    /** Where the thumbnail reads go; null counts as the film's server. */
    var source: String? = null

    /** The reads are the film's own file, so always on the film's server whatever the link looks like. */
    var filmFile = false

    private val times = ArrayDeque<Long>()
    private val aheads = ArrayDeque<Long>()
    private var changedAtMs = NONE
    private var troubleAtMs = NONE
    private var goodSinceMs = NONE
    private var seekSeen = 0L
    private var fromPause = false
    private var shortSinceMs = NONE
    private var roomySinceMs = NONE
    private var shrunk = false
    private var judged = false
    private var hardStop = false
    private var wasPlaying = false

    /** One look at playback. True when [lanes] changed. */
    fun note(nowMs: Long, playing: Boolean, buffering: Boolean, loading: Boolean, aheadMs: Long, toEnd: Boolean): Boolean {
        val before = lanes
        hardStop = false
        val s = line(nowMs)
        surplus = s != null
        val window = if (s != null) TREND_WINDOW_MS else FALL_WINDOW_MS
        if (loading && playing && !buffering) {
            times.addLast(nowMs)
            aheads.addLast(aheadMs)
            while (times.isNotEmpty() && nowMs - times.first() > window) {
                times.removeFirst()
                aheads.removeFirst()
            }
        } else {
            times.clear()
            aheads.clear()
        }
        when {
            buffering -> trouble(nowMs, "playback buffering")
            !playing -> {
                lanes = max
                why = "not playing"
                fromPause = true
                judged = false
            }
            toEnd -> {
                lanes = max
                why = "buffered to the end"
                judged = false
            }
            s != null -> surplus(nowMs, aheadMs, s)
            else -> {
                judged = false
                fixedMark(nowMs, aheadMs)
            }
        }
        if (s != null) seekSeen = maxOf(seekSeen, s.seekAtMs)
        if (playing) fromPause = false
        val playedBefore = wasPlaying
        wasPlaying = playing
        if (lanes == before) return false
        if (hardStop && lanes < before && (playing || (buffering && playedBefore))) gaveWay++
        changedAtMs = nowMs
        return true
    }

    private fun surplus(nowMs: Long, aheadMs: Long, s: PlaybackLine.State) {
        val fell = (aheads.firstOrNull() ?: aheadMs) - aheadMs
        val onRoom = judged
        judged = true
        val sameServer = filmFile || onFilmServer(source, s)
        val room = (if (sameServer) filmRoom(s) else hostRoom(source!!)).coerceAtMost(max)
        val rate = if (s.loaderRested) "loader resting" else "downloads ${mbit(s.deliveredBps)} of ${mbit(s.filmBps)} Mbit/s"
        val roomNote = if (sameServer) "room $room of ${s.serverLimit} (playback ${s.requests}, needs ${s.needed}" +
            (if (s.otherRequests > 0) ", other ${s.otherRequests}" else "") + ")"
            else "room $room on the copy's server"
        val stop = when {
            s.seekAtMs > seekSeen -> "a seek"
            nowMs - s.refusedAtMs < REFUSAL_HOLD_MS || refused(source) -> "too many requests"
            aheadMs < FLOOR_MS -> "buffer ${seconds(aheadMs)} under ${seconds(FLOOR_MS)}"
            fell > FALL_TOLERANCE_MS -> "buffer ${seconds(aheadMs)}, down ${seconds(fell)} in ${TREND_WINDOW_MS / 1000} s"
            room <= 0 -> "playback needs its requests, $roomNote"
            else -> null
        }
        val wait = when {
            stop != null -> null
            s.filmBps <= 0L -> "the film's rate not known yet"
            !s.loaderRested && s.deliveredBps <= 0L -> "the downloads' rate not known yet"
            !rateCarries(s) -> "buffer ${seconds(aheadMs)}, $rate, under ${SURPLUS_RATE_PERCENT / 100.0}x"
            else -> null
        }
        if (stop != null || wait != null) {
            shortSinceMs = NONE
            trouble(nowMs, stop ?: wait!!, hard = stop != null)
            return
        }
        if (goodSinceMs == NONE) goodSinceMs = if (fromPause) nowMs - GOOD_FOR_MS else nowMs
        val trend = if (times.isEmpty()) "full" else if (fell < 0L) "rising" else "steady"
        val ready = nowMs - goodSinceMs >= GOOD_FOR_MS
        why = "surplus: $rate, buffer ${seconds(aheadMs)} $trend, $roomNote" +
            if (!ready) ", waiting ${(GOOD_FOR_MS - (nowMs - goodSinceMs) + 999) / 1000} s more" else ""
        if (!ready) {
            lanes = 0
            shortSinceMs = NONE
            roomySinceMs = NONE
        } else if (lanes > room) {
            roomySinceMs = NONE
            if (shortSinceMs == NONE) shortSinceMs = nowMs
            if (!onRoom || nowMs - shortSinceMs >= SHRINK_AFTER_MS) {
                // Back from a pause the lanes simply follow the room; while playing the reads under way finish.
                if (onRoom) shrunk = true
                lanes = room
                shortSinceMs = NONE
            } else {
                why += ", ${lanes - room} over for ${seconds(nowMs - shortSinceMs)}"
            }
        } else {
            shortSinceMs = NONE
            if (lanes < room) {
                if (roomySinceMs == NONE) roomySinceMs = nowMs
                if (since(changedAtMs, nowMs) && (!shrunk || nowMs - roomySinceMs >= GOOD_FOR_MS)) {
                    lanes++
                    roomySinceMs = NONE
                }
            } else {
                roomySinceMs = NONE
            }
        }
    }

    private fun fixedMark(nowMs: Long, aheadMs: Long) {
        val fell = (aheads.maxOrNull() ?: aheadMs) - aheadMs
        when {
            aheadMs < LOW_AHEAD_MS -> trouble(nowMs, "buffer ${seconds(aheadMs)}")
            fell >= FAST_FALL_MS ->
                trouble(nowMs, "buffer ${seconds(aheadMs)}, down ${seconds(fell)} in ${FALL_WINDOW_MS / 1000} s")
            else -> {
                why = "buffer ${seconds(aheadMs)}"
                if (lanes < max && aheadMs >= BACK_AHEAD_MS && since(troubleAtMs, nowMs) && since(changedAtMs, nowMs)) {
                    lanes++
                }
            }
        }
    }

    private fun trouble(nowMs: Long, what: String, hard: Boolean = true) {
        hardStop = hard
        if (hard) shrunk = false
        lanes = 0
        why = what
        troubleAtMs = nowMs
        goodSinceMs = NONE
    }

    private fun since(atMs: Long, nowMs: Long): Boolean = atMs == NONE || nowMs - atMs >= STEP_UP_MS
}
