package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream

/**
 * "Best quality first" for the stream list: the order Best quality auto-play ranks by, worked out once for every
 * link that arrived, then applied to whichever view is shown (All, or one source). Links the Quality rules exclude
 * are left out; a view made only of excluded links shows them all, ranked, the way auto-play falls back.
 */
class StreamListOrder private constructor(
    private val position: Map<String, Int>,
    private val excluded: Set<String>,
    private val keyOf: (Stream) -> String
) {
    fun apply(streams: List<Stream>): List<Stream> {
        val keyed = streams.map { it to keyOf(it) }
        val kept = keyed.filter { (_, key) -> key !in excluded }.ifEmpty { keyed }
        return kept.sortedBy { (_, key) -> position[key] ?: Int.MAX_VALUE }.map { it.first }
    }

    fun covers(streams: List<Stream>): Boolean = streams.all { keyOf(it) in position }

    companion object {
        fun of(
            streams: List<Stream>,
            preferences: DebridStreamPreferences?,
            keyOf: (Stream) -> String
        ): StreamListOrder {
            val order = StreamQualityRank.order(streams, preferences)
            val position = HashMap<String, Int>(streams.size * 2)
            order.bestFirst.forEachIndexed { rank, index -> position.putIfAbsent(keyOf(streams[index]), rank) }
            val excluded = order.excluded.mapTo(HashSet()) { keyOf(streams[it]) }
            return StreamListOrder(position, excluded, keyOf)
        }
    }
}
