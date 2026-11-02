package com.nuvio.tv.core.player.thumbnail

import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import okhttp3.OkHttpClient

/** Playback's client (HTTP/1.1); thumbnail reads may reuse its idle connections but never share one at the same time. */
internal fun thumbnailHttpClient(headers: Map<String, String>): OkHttpClient =
    PlayerPlaybackNetworking.createHttpClient(headers)
