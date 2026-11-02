package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.EpisodeOptionsOverlayStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeOptionsOverlayTest {
    @Test
    fun `none style does not show episode artwork`() {
        assertFalse(shouldShowEpisodeOverlayBackdrop(EpisodeOptionsOverlayStyle.NONE))
        assertFalse(
            shouldBlurEpisodeOverlayBackdrop(
                style = EpisodeOptionsOverlayStyle.NONE,
                blurUnwatchedEpisodes = true,
                isWatched = false
            )
        )
    }

    @Test
    fun `artwork style uses sharp artwork by default`() {
        assertTrue(shouldShowEpisodeOverlayBackdrop(EpisodeOptionsOverlayStyle.ARTWORK))
        assertFalse(
            shouldBlurEpisodeOverlayBackdrop(
                style = EpisodeOptionsOverlayStyle.ARTWORK,
                blurUnwatchedEpisodes = false,
                isWatched = false
            )
        )
    }

    @Test
    fun `artwork style respects unwatched blur preference`() {
        assertTrue(
            shouldBlurEpisodeOverlayBackdrop(
                style = EpisodeOptionsOverlayStyle.ARTWORK,
                blurUnwatchedEpisodes = true,
                isWatched = false
            )
        )
        assertFalse(
            shouldBlurEpisodeOverlayBackdrop(
                style = EpisodeOptionsOverlayStyle.ARTWORK,
                blurUnwatchedEpisodes = true,
                isWatched = true
            )
        )
    }

    @Test
    fun `blur style always blurs episode artwork`() {
        assertTrue(shouldShowEpisodeOverlayBackdrop(EpisodeOptionsOverlayStyle.BLUR))
        assertTrue(
            shouldBlurEpisodeOverlayBackdrop(
                style = EpisodeOptionsOverlayStyle.BLUR,
                blurUnwatchedEpisodes = false,
                isWatched = true
            )
        )
    }

    @Test
    fun `overlay offers every episode action when all apply`() {
        assertEquals(
            listOf(
                EpisodeOverlayActionKind.TOGGLE_WATCHED,
                EpisodeOverlayActionKind.TOGGLE_SEASON_WATCHED,
                EpisodeOverlayActionKind.MARK_PREVIOUS_WATCHED,
                EpisodeOverlayActionKind.PLAY,
                EpisodeOverlayActionKind.OPEN_COMMENTS,
                EpisodeOverlayActionKind.PLAY_MANUALLY,
                EpisodeOverlayActionKind.START_FROM_BEGINNING
            ),
            episodeOverlayActionKinds(
                hasPreviousEpisodes = true,
                showOpenEpisodeComments = true,
                showPlayManually = true,
                hasProgress = true,
                isPlayEnabled = true
            )
        )
    }

    @Test
    fun `overlay keeps only the watched and play actions for a first unstarted episode`() {
        assertEquals(
            listOf(
                EpisodeOverlayActionKind.TOGGLE_WATCHED,
                EpisodeOverlayActionKind.TOGGLE_SEASON_WATCHED,
                EpisodeOverlayActionKind.PLAY
            ),
            episodeOverlayActionKinds(
                hasPreviousEpisodes = false,
                showOpenEpisodeComments = false,
                showPlayManually = false,
                hasProgress = false,
                isPlayEnabled = true
            )
        )
    }

    @Test
    fun `overlay hides play manually and start over when the episode cannot play`() {
        val kinds = episodeOverlayActionKinds(
            hasPreviousEpisodes = true,
            showOpenEpisodeComments = true,
            showPlayManually = true,
            hasProgress = true,
            isPlayEnabled = false
        )
        assertTrue(EpisodeOverlayActionKind.PLAY in kinds)
        assertFalse(EpisodeOverlayActionKind.PLAY_MANUALLY in kinds)
        assertFalse(EpisodeOverlayActionKind.START_FROM_BEGINNING in kinds)
        assertFalse(isEpisodeOverlayActionEnabled(EpisodeOverlayActionKind.PLAY, isPending = false, isPlayEnabled = false))
    }

    @Test
    fun `pending watched change disables only the watched toggle`() {
        EpisodeOverlayActionKind.entries.forEach { kind ->
            assertEquals(
                kind != EpisodeOverlayActionKind.TOGGLE_WATCHED,
                isEpisodeOverlayActionEnabled(kind, isPending = true, isPlayEnabled = true)
            )
        }
    }
}
