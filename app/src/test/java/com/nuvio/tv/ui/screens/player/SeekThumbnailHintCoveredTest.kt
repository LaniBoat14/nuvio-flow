package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekThumbnailHintCoveredTest {

    private val paused = PlayerUiState(isPlaying = false, showControls = true)

    @Test
    fun `hint shows while paused with nothing open`() {
        assertFalse(seekThumbnailHintCovered(paused))
        assertFalse(seekThumbnailHintCovered(paused.copy(showControls = false)))
    }

    @Test
    fun `small badges and stats do not hide the hint`() {
        assertFalse(seekThumbnailHintCovered(paused.copy(showPlaybackStatsOverlay = true)))
        assertFalse(seekThumbnailHintCovered(paused.copy(showParentalGuide = true)))
        assertFalse(seekThumbnailHintCovered(paused.copy(showDisplayModeInfo = true)))
    }

    @Test
    fun `details overlay hides the hint`() {
        assertTrue(seekThumbnailHintCovered(paused.copy(showPauseOverlay = true, showControls = false)))
    }

    @Test
    fun `every side panel and menu hides the hint`() {
        val covering = listOf<(PlayerUiState) -> PlayerUiState>(
            { it.copy(showSourcesPanel = true) },
            { it.copy(showEpisodesPanel = true) },
            { it.copy(showAudioOverlay = true) },
            { it.copy(showSubtitleOverlay = true) },
            { it.copy(showSubtitleStylePanel = true) },
            { it.copy(showSubtitleTimingDialog = true) },
            { it.copy(showSubtitleDelayOverlay = true) },
            { it.copy(showSpeedDialog = true) },
            { it.copy(showMoreDialog = true) },
            { it.copy(showStreamInfoOverlay = true) },
            { it.copy(showPartyPanel = true) }
        )
        covering.forEachIndexed { index, open ->
            assertTrue("case $index", seekThumbnailHintCovered(open(paused)))
        }
    }

    @Test
    fun `next episode card and still watching prompt hide the hint`() {
        val next = NextEpisodeInfo(
            videoId = "tt1:1:2", season = 1, episode = 2, title = "Two", thumbnail = null,
            overview = null, released = null, hasAired = true, unairedMessage = null
        )
        assertTrue(seekThumbnailHintCovered(paused.copy(postPlayMode = PostPlayMode.StillWatching(next))))
        assertTrue(seekThumbnailHintCovered(paused.copy(postPlayMode = PostPlayMode.AutoPlay(next))))
    }
}
