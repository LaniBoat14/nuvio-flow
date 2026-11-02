package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamListOrderTest {
    private val server1080 = stream("Jellyfin", "Movie.2023.1080p.WEB-DL.DDP5.1-FLUX.mkv")
    private val torrentio720 = stream("Torrentio", "Movie.2023.720p.WEB-DL.DDP5.1-FLUX.mkv")
    private val torrentioRemux = stream("Torrentio", "Movie.2023.2160p.UHD.BluRay.REMUX.TrueHD.7.1-FraMeSToR.mkv")
    private val torrentioYify = stream("Torrentio", "Movie.2023.2160p.BluRay.x265-YIFY.mkv")
    private val aioWeb4k = stream("AIOStreams", "Movie.2023.2160p.WEB-DL.DDP5.1-FLUX.mkv")
    private val sourceOrder = listOf(server1080, torrentio720, torrentioRemux, torrentioYify, aioWeb4k)

    private fun order(streams: List<Stream> = sourceOrder) = StreamListOrder.of(streams, null) { it.key() }

    @Test fun allIsOneRankedListWithMediaServersRankedWithTheRest() {
        assertEquals(listOf(torrentioRemux, aioWeb4k, server1080, torrentio720), order().apply(sourceOrder))
    }

    @Test fun linksTheQualityRulesExcludeAreHidden() {
        assertFalse(torrentioYify in order().apply(sourceOrder))
    }

    @Test fun eachSourceIsRankedOnItsOwn() {
        val torrentio = sourceOrder.filter { it.addonName == "Torrentio" }
        assertEquals(listOf(torrentioRemux, torrentio720), order().apply(torrentio))
        assertEquals(listOf(server1080), order().apply(sourceOrder.filter { it.addonName == "Jellyfin" }))
    }

    @Test fun theOrderIsTheOneBestQualityAutoPlayUses() {
        assertEquals(StreamQualityRank.rank(sourceOrder), order().apply(sourceOrder))
    }

    @Test fun aViewWithOnlyExcludedLinksStillShowsThem() {
        val yifyOnly = listOf(torrentioYify)
        assertEquals(yifyOnly, order().apply(yifyOnly))
    }

    @Test fun linksThatArriveLaterGoLastUntilRankedAgain() {
        val ranked = order(sourceOrder.take(3))
        val withLate = sourceOrder
        assertFalse(ranked.covers(withLate))
        val shown = ranked.apply(withLate)
        assertEquals(listOf(torrentioRemux, server1080, torrentio720), shown.take(3))
        assertEquals(setOf(torrentioYify, aioWeb4k), shown.drop(3).toSet())
        assertTrue(order().covers(withLate))
    }

    private fun Stream.key() = "$addonName|${behaviorHints?.filename}"

    private fun stream(addon: String, filename: String): Stream = Stream(
        name = filename,
        title = null,
        description = null,
        url = "https://example.com/$addon/$filename",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = filename
        ),
        addonName = addon,
        addonLogo = null
    )
}
