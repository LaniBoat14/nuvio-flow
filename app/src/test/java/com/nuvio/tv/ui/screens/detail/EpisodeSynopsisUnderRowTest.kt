package com.nuvio.tv.ui.screens.detail

import androidx.compose.ui.geometry.Rect
import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeSynopsisUnderRowTest {
    private fun video(id: String, episode: Int?, overview: String?, title: String = "Episode $id") = Video(
        id = id,
        title = title,
        released = null,
        thumbnail = null,
        season = 1,
        episode = episode,
        overview = overview
    )

    private val episodes = listOf(
        video("e1", 1, "Islands are wildlife sanctuaries."),
        video("e2", 2, "  The high peaks of the world.  "),
        video("e3", 3, null),
        video("e4", 4, "   ")
    )

    @Test fun `the block is only for the V2 look, when switched on and the row has synopsis data`() {
        assertTrue(showEpisodeSynopsisUnderRow(settingOn = true, compactLook = true, rowHasSynopsis = true))
        assertFalse(showEpisodeSynopsisUnderRow(settingOn = false, compactLook = true, rowHasSynopsis = true))
        assertFalse(showEpisodeSynopsisUnderRow(settingOn = true, compactLook = false, rowHasSynopsis = true))
        assertFalse(showEpisodeSynopsisUnderRow(settingOn = true, compactLook = true, rowHasSynopsis = false))
    }

    @Test fun `a row has synopsis data when any episode carries text`() {
        assertTrue(rowHasEpisodeSynopsis(episodes))
        assertFalse(rowHasEpisodeSynopsis(listOf(video("a", 1, null), video("b", 2, " "))))
        assertFalse(rowHasEpisodeSynopsis(emptyList()))
    }

    @Test fun `the line carries the number, the title and the trimmed synopsis`() {
        val line = episodeSynopsisLine(episodes, "e2")
        assertEquals(EpisodeSynopsisLine(2, "Episode e2", "The high peaks of the world."), line)
    }

    @Test fun `an episode without text keeps its title line and no synopsis`() {
        assertEquals(EpisodeSynopsisLine(3, "Episode e3", null), episodeSynopsisLine(episodes, "e3"))
        assertEquals(EpisodeSynopsisLine(4, "Episode e4", null), episodeSynopsisLine(episodes, "e4"))
    }

    @Test fun `no line for an unknown or missing episode`() {
        assertNull(episodeSynopsisLine(episodes, "nope"))
        assertNull(episodeSynopsisLine(episodes, null))
        assertNull(episodeSynopsisLine(emptyList(), "e1"))
    }

    @Test fun `before any focus the block shows the episode that will take focus`() {
        assertEquals("e2", restingEpisodeSynopsisId(episodes, anchorEpisodeId = "e3", restoreEpisodeId = "e2", scrollToEpisodeId = "e4"))
        assertEquals("e3", restingEpisodeSynopsisId(episodes, anchorEpisodeId = "e3", restoreEpisodeId = null, scrollToEpisodeId = "e4"))
        assertEquals("e2", restingEpisodeSynopsisId(episodes, anchorEpisodeId = null, restoreEpisodeId = "e2", scrollToEpisodeId = "e4"))
        assertEquals("e4", restingEpisodeSynopsisId(episodes, anchorEpisodeId = null, restoreEpisodeId = null, scrollToEpisodeId = "e4"))
        assertEquals("e1", restingEpisodeSynopsisId(episodes, anchorEpisodeId = "gone", restoreEpisodeId = null, scrollToEpisodeId = null))
        assertNull(restingEpisodeSynopsisId(emptyList(), anchorEpisodeId = "e1", restoreEpisodeId = null, scrollToEpisodeId = null))
    }

    @Test fun `the first focus shows at once and a move along the row waits a little`() {
        assertEquals(0L, episodeSynopsisSettleMs(shownEpisodeId = null, nextEpisodeId = "e1"))
        assertEquals(0L, episodeSynopsisSettleMs(shownEpisodeId = "e1", nextEpisodeId = "e1"))
        assertEquals(0L, episodeSynopsisSettleMs(shownEpisodeId = "e1", nextEpisodeId = null))
        assertEquals(EPISODE_SYNOPSIS_SETTLE_MS, episodeSynopsisSettleMs(shownEpisodeId = "e1", nextEpisodeId = "e2"))
        assertTrue(EPISODE_SYNOPSIS_SETTLE_MS in 80L..250L)
    }

    @Test fun `a vertical move into the row also shows the block, a sideways move never scrolls`() {
        val card = Rect(10f, 20f, 210f, 160f)
        assertEquals(Rect(10f, 0f, 210f, 0f), episodeRowBringIntoViewRect(card, stayVertical = true, rowBottom = 400f, blockShown = true))
        assertEquals(Rect(10f, 20f, 210f, 400f), episodeRowBringIntoViewRect(card, stayVertical = false, rowBottom = 400f, blockShown = true))
        assertEquals(card, episodeRowBringIntoViewRect(card, stayVertical = false, rowBottom = 400f, blockShown = false))
        assertEquals(card, episodeRowBringIntoViewRect(card, stayVertical = false, rowBottom = 100f, blockShown = true))
    }
}
