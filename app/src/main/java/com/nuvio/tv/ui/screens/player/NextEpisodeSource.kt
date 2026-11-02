package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream

/** The add-on and debrid service a stream came from, as far as its label tells. */
internal data class NextEpisodeSource(val addonName: String?, val service: String?) {
    val isKnown: Boolean get() = addonName != null || service != null

    fun matches(stream: Stream): Boolean =
        (addonName == null || stream.addonName == addonName) && serviceOf(stream) == service

    /** Whether the add-on has answered, so a match elsewhere no longer has to wait for it. */
    fun hasResponded(data: List<AddonStreams>): Boolean =
        addonName == null || data.any { it.addonName == addonName }

    companion object {
        fun of(addonName: String?, streamName: String?, streamDescription: String?) = NextEpisodeSource(
            addonName = addonName?.takeIf { it.isNotBlank() },
            service = debridStoreOf(streamName ?: addonName, streamDescription)
        )

        fun serviceOf(stream: Stream): String? = debridStoreOf(stream.name ?: stream.addonName, stream.description)

        /** Manual mode picks the next episode only from the same add-on and service unless fallback is on. */
        fun sameSourceOnly(settings: PlayerSettings): Boolean =
            settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
                (settings.streamAutoPlayNextEpisodeEnabled || settings.streamAutoPlayPreferBingeGroupForNextEpisode) &&
                !(settings.streamAutoPlayNextEpisodeEnabled && settings.streamAutoPlayNextEpisodeFallbackEnabled)
    }
}

/**
 * Runs [pick] on the streams from [source] first, then on all of them when [allowOtherSources] is set.
 * An unknown source leaves the streams as they are.
 */
internal fun pickSameSourceFirst(
    streams: List<Stream>,
    source: NextEpisodeSource,
    allowOtherSources: Boolean,
    pick: (List<Stream>) -> Stream?
): Stream? {
    if (!source.isKnown) return pick(streams)
    val sameSource = streams.filter(source::matches)
    if (sameSource.isNotEmpty()) pick(sameSource)?.let { return it }
    return if (allowOtherSources) pick(streams) else null
}
