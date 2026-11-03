package com.nuvio.tv.ui.screens.player

import android.util.Log

/**
 * "Generate thumbnails before play": holds the player like a user pause while the title's thumbnails are made.
 * True when this holds it; false when the user already paused (not ours to hold, and never ours to resume); null
 * when this start cannot be held yet (the engine asks again after the first frame).
 *
 * Flow holds with the same flag a user pause sets, so nothing resumes the player on its own; the engine ends the
 * hold with `player.play()` (or [loadAgainAfterSeekThumbnails]), which clears the flag as playback starts.
 */
internal fun PlayerRuntimeController.pauseForSeekThumbnails(): Boolean? {
    if (isUsingMpvEngine()) return false
    // In a party the room decides when this box plays; a hold here would pause everyone.
    if (isPartyControlLocked) return null
    val player = _exoPlayer ?: return false
    if (userPausedManually || !player.playWhenReady) return false
    // The first frame of a tunnelled or Dolby Vision start must be up before anything pauses it.
    if (!hasRenderedFirstFrame) return null
    shouldEnforceAutoplayOnFirstReady = false
    userPausedManually = true
    player.pause()
    cancelPauseOverlay()
    Log.i(PlayerRuntimeController.TAG, "prepare: playback held for the seek thumbnails")
    return true
}

/** Flow keeps its decoders and buffer while held; only Arc's small-box 4K path lets them go. */
@Suppress("UnusedReceiverParameter")
internal fun PlayerRuntimeController.letGoForSeekThumbnails(): Boolean = false

/** Ends a hold: the film plays on, or stays paused for the user when the app was left meanwhile. */
internal fun PlayerRuntimeController.loadAgainAfterSeekThumbnails(play: Boolean) {
    val player = _exoPlayer ?: return
    if (!play) return
    if (!player.playWhenReady) {
        userPausedManually = false
        player.play()
    }
}
