package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.debrid.CopyRoute
import com.nuvio.tv.core.debrid.DebridStreamFormatter
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.DebridStreamTemplateEngine
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamSortCriterion
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCopyCandidatesTest {
    private val presentation = DebridStreamPresentation(
        dataStore = mockk(),
        streamBadgeSettingsDataStore = mockk(),
        formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
    )

    private val settings = DebridSettings(
        enabled = true,
        torboxApiKey = "test-key",
        streamPreferences = DebridStreamPreferences(
            maxResults = 0,
            requiredResolutions = emptyList(),
            excludedResolutions = listOf(
                DebridStreamResolution.P720, DebridStreamResolution.P576,
                DebridStreamResolution.P480, DebridStreamResolution.P360
            ),
            excludedReleaseGroups = listOf("SGF"),
            sortCriteria = DebridStreamSortCriterion.originalOrder
        )
    )

    @Test
    fun `a 720p TorBox entry kept off the list by resolution is offered as a copy only`() {
        val group = AddonStreams(
            addonName = "Torbox Instant",
            addonLogo = null,
            streams = listOf(
                debrid("2160p", hash = "a".repeat(40)),
                debrid("720p", hash = "b".repeat(40), codec = "AVC"),
                debrid("720p", hash = "c".repeat(40), quality = "CAM"),
                debrid("720p", hash = "d".repeat(40), group = "SGF")
            )
        )

        val presented = presentation.apply(listOf(group), settings).single()

        assertEquals(listOf("a".repeat(40)), presented.streams.map { it.clientResolve?.infoHash })
        assertEquals(listOf("b".repeat(40)), presented.thumbnailCopyStreams.map { it.clientResolve?.infoHash })

        val candidates = copyCandidates(presented.streams, presented.thumbnailCopyStreams)
        assertEquals(presented.streams, candidates.take(presented.streams.size))
        val copy = candidates.last()
        assertEquals("b".repeat(40), copy.clientResolve?.infoHash)
        assertTrue(SourceFailoverSelection.isAutoFailoverPlayable(copy, null, null))
        assertEquals(
            CopyRoute.Torbox,
            CopyRoute.of(copy.clientResolve?.service, hasHash = true, torboxKey = true, premiumizeKey = false)
        )
    }

    @Test
    fun `a later batch for the same source keeps the list as it was`() {
        val first = presentation.apply(
            listOf(AddonStreams("Torbox Instant", null, listOf(debrid("1080p", hash = "a".repeat(40)), debrid("720p", hash = "b".repeat(40))))),
            settings
        ).single()
        val merged = first.copy(streams = first.streams + first.thumbnailCopyStreams + debrid("720p", hash = "e".repeat(40)))

        val again = presentation.apply(listOf(merged), settings).single()

        assertEquals(first.streams.map { it.clientResolve?.infoHash }, again.streams.map { it.clientResolve?.infoHash })
        assertEquals(listOf("b".repeat(40), "e".repeat(40)), again.thumbnailCopyStreams.map { it.clientResolve?.infoHash })
    }

    @Test
    fun `without resolution rules the source list is the whole pool`() {
        val listed = listOf(debrid("720p", hash = "a".repeat(40)))

        assertSame(listed, copyCandidates(listed, emptyList()))
    }

    private fun debrid(
        resolution: String,
        hash: String,
        codec: String? = null,
        quality: String? = null,
        group: String? = null
    ): Stream {
        val filename = "Film.$resolution.${quality ?: "BluRay"}.${codec ?: "HEVC"}-${group ?: "GRP"}.mkv"
        return Stream(
            name = "Torbox Instant",
            title = null,
            description = null,
            url = null,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Torbox Instant",
            addonLogo = null,
            clientResolve = StreamClientResolve(
                type = "debrid",
                infoHash = hash,
                fileIdx = 1,
                magnetUri = "magnet:?xt=urn:btih:$hash",
                sources = null,
                torrentName = filename,
                filename = filename,
                mediaType = "movie",
                mediaId = "tt1",
                mediaOnlyId = "tt1",
                title = "Film",
                season = null,
                episode = null,
                service = "torbox",
                serviceIndex = 0,
                serviceExtension = null,
                isCached = true,
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        torrentName = filename,
                        filename = filename,
                        size = 4_000_000_000L,
                        folderSize = 4_000_000_000L,
                        tracker = null,
                        indexer = null,
                        network = null,
                        parsed = StreamClientResolveParsed(
                            rawTitle = null,
                            parsedTitle = null,
                            year = null,
                            resolution = resolution,
                            seasons = null,
                            episodes = null,
                            quality = quality,
                            hdr = emptyList(),
                            codec = codec,
                            audio = null,
                            channels = null,
                            languages = null,
                            group = group,
                            network = null,
                            edition = null,
                            duration = null,
                            bitDepth = null,
                            extended = null,
                            theatrical = null,
                            remastered = null,
                            unrated = null
                        )
                    )
                )
            )
        )
    }
}
