package com.nuvio.tv.core.recommendations

import android.net.Uri
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramBuilderTest {

    @Test
    fun `watch next content id match does not delete prefix collisions`() {
        assertTrue(watchNextIdMatchesContentId("wn_tt1", "tt1"))
        assertTrue(watchNextIdMatchesContentId("wn_tt1_s2e3", "tt1"))

        assertFalse(watchNextIdMatchesContentId("wn_tt10", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt10_s2e3", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt1extra", "tt1"))
        assertFalse(watchNextIdMatchesContentId("wn_tt1_series", "tt1"))
        assertFalse(watchNextIdMatchesContentId(null, "tt1"))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `the title logo goes on the program and a blank one is left off`() {
        stubUri()
        val builder = ProgramBuilder(mockk(relaxed = true))

        builder.buildWatchNextProgram(progress().copy(logo = "https://img/logo.png"), "A synopsis")
        builder.buildWatchNextProgram(progress().copy(logo = " "), null)

        verify(exactly = 1) { Uri.parse("https://img/logo.png") }
        verify(exactly = 0) { Uri.parse(" ") }
    }

    @Test
    fun `synopsis prefers known text, then the episode overview, then the show`() {
        val episode = progress().copy(contentType = "series", videoId = "tt1:2:3", season = 2, episode = 3)
        val meta = meta(
            description = "Show text",
            videos = listOf(video("tt1:2:2", 2, 2, "Other episode"), video("tt1:2:3", 2, 3, " Episode text "))
        )

        assertEquals("Known", watchNextDescription(episode, meta, "Known"))
        assertEquals("Episode text", watchNextDescription(episode, meta))
        assertEquals("Episode text", watchNextDescription(episode.copy(videoId = "other"), meta))
        assertEquals("Show text", watchNextDescription(episode.copy(episode = 9, videoId = "x"), meta))
        assertEquals("Show text", watchNextDescription(progress(), meta))
        assertNull(watchNextDescription(progress(), meta(description = " ", videos = emptyList()), " "))
        assertNull(watchNextDescription(progress(), null))
    }

    private fun stubUri() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val text = firstArg<String?>() ?: ""
            mockk<Uri>(relaxed = true) { every { this@mockk.toString() } returns text }
        }
    }

    private fun progress() = WatchProgress(
        contentId = "tt1",
        contentType = "movie",
        name = "Title",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1",
        season = null,
        episode = null,
        episodeTitle = null,
        position = 60_000L,
        duration = 5_400_000L,
        lastWatched = 1_700_000_000_000L
    )

    private fun meta(description: String?, videos: List<Video>) = mockk<Meta> {
        every { this@mockk.description } returns description
        every { this@mockk.videos } returns videos
    }

    private fun video(id: String, season: Int, episode: Int, overview: String?) = mockk<Video> {
        every { this@mockk.id } returns id
        every { this@mockk.season } returns season
        every { this@mockk.episode } returns episode
        every { this@mockk.overview } returns overview
    }

}
