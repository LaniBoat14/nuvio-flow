package com.nuvio.tv.data.local

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Snapshots written by nt.7.4.1 have no ratingVisible field: their ratings stay on screen after the update. */
class ContinueWatchingSnapshotUpgradeTest {

    private val nextUp741 = """[{"contentId":"tt0903747","contentType":"series","name":"Breaking Bad","poster":"https://img/p.jpg",
        "videoId":"tt0903747:2:3","season":2,"episode":3,"episodeTitle":"Bit by a Dead Bee","thumbnail":"https://img/t.jpg",
        "hasAired":true,"lastWatched":1760000000000,"imdbRating":8.6,"genres":["Drama"],"releaseInfo":"2008","sortTimestamp":1760000000000,
        "isReleaseAlert":false,"isNewSeasonRelease":false,"seedSeason":2,"seedEpisode":2,"contentLanguage":"en"}]"""

    private val inProgress741 = """[{"contentId":"tt0133093","contentType":"movie","name":"The Matrix","poster":"https://img/m.jpg",
        "videoId":"tt0133093","position":600000,"duration":8160000,"lastWatched":1760000000000,"progressPercent":7.3,
        "episodeImdbRating":8.7,"genres":["Action"],"releaseInfo":"1999"}]"""

    @Test
    fun `nt 7_4_1 snapshots read as showing the rating`() = runBlocking {
        val dir = Files.createTempDirectory("cw-upgrade-").toFile()
        try {
            File(dir, "cw_enrichment").mkdirs()
            File(dir, "cw_enrichment/nextup_1.json").writeText(nextUp741)
            File(dir, "cw_enrichment/inprogress_1.json").writeText(inProgress741)
            val context = mockk<Context>()
            every { context.filesDir } returns dir
            val manager = mockk<ProfileManager>()
            every { manager.activeProfileId } returns MutableStateFlow(1)
            val cache = ContinueWatchingEnrichmentCache(context, manager)

            val next = cache.getNextUpSnapshot(1).single()
            assertNotEquals(false, next.ratingVisible)
            assertEquals(8.6f, next.imdbRating)
            val progress = cache.getInProgressSnapshot(1).single()
            assertNotEquals(false, progress.ratingVisible)
            assertEquals(8.7f, progress.episodeImdbRating)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a hidden rating written by this version stays hidden`() = runBlocking {
        val dir = Files.createTempDirectory("cw-hidden-").toFile()
        try {
            val context = mockk<Context>()
            every { context.filesDir } returns dir
            val manager = mockk<ProfileManager>()
            every { manager.activeProfileId } returns MutableStateFlow(1)
            val cache = ContinueWatchingEnrichmentCache(context, manager)
            val item = CachedNextUpItem("tt1", "series", "Show", null, null, null, "tt1:1:2", 1, 2, null,
                thumbnail = null, lastWatched = 1, sortTimestamp = 1, imdbRating = 7f, ratingVisible = false)
            cache.saveNextUpSnapshot(listOf(item), force = true)
            assertEquals(false, cache.getNextUpSnapshot(1).single().ratingVisible)
        } finally {
            dir.deleteRecursively()
        }
    }
}
