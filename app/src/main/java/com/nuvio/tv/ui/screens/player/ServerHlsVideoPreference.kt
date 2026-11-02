package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

internal val SERVER_HLS_VIDEO_MIME_ORDER = arrayOf(
    MimeTypes.VIDEO_DOLBY_VISION,
    MimeTypes.VIDEO_H265,
    MimeTypes.VIDEO_AV1,
    MimeTypes.VIDEO_H264
)

/**
 * Jellyfin and Emby put an H.264 SDR copy next to a copied HDR or Dolby Vision video in the same HLS playlist, at the
 * same bitrate. Ranking it last keeps it as the fallback for boxes that cannot decode the original.
 */
internal fun serverHlsVideoMimeTypes(serverHls: Boolean, current: List<String>): Array<String>? =
    if (serverHls && current.isEmpty()) SERVER_HLS_VIDEO_MIME_ORDER else null

internal fun DefaultTrackSelector.Parameters.preferServerHlsVideo(serverHls: Boolean): DefaultTrackSelector.Parameters =
    serverHlsVideoMimeTypes(serverHls, preferredVideoMimeTypes)
        ?.let { buildUpon().setPreferredVideoMimeTypes(*it).build() }
        ?: this
