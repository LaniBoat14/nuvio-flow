package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.streams.StreamBadgeMatcher
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.ui.components.StreamImportedBadgeChip
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

sealed interface PlayerBadge {
    data class Pack(val badge: StreamBadge) : PlayerBadge
    data class BuiltIn(@DrawableRes val res: Int) : PlayerBadge
}

@Composable
internal fun rememberPlayerBadges(viewModel: PlayerViewModel, uiState: PlayerUiState): List<PlayerBadge> {
    val filters = uiState.playerBadgeFilters
    val enabled = uiState.showPlayerBadges
    // A new stream starts clean, so its row never carries the last stream's audio.
    var mediaInfo by remember(uiState.currentStreamUrl) { mutableStateOf(PlayerMediaInfo()) }
    var releaseText by remember(uiState.currentStreamUrl) { mutableStateOf("") }
    var fileName by remember(uiState.currentStreamUrl) { mutableStateOf("") }
    val selectedAudio = uiState.audioTracks.firstOrNull { it.isSelected }
    LaunchedEffect(
        enabled,
        filters,
        uiState.showControls,
        uiState.isBuffering,
        uiState.currentStreamUrl,
        uiState.internalPlayerEngine,
        selectedAudio?.index
    ) {
        // Read while it buffers too: the tracks are known before the first frame, so the row is
        // filled in by the time playback starts. Buffering stays a key so the end of it reads at once.
        if (!enabled || !uiState.showControls) return@LaunchedEffect
        // Read again while the row shows: adaptive streams change resolution, and mpv knows the picture only once decoded.
        var quickReads = 0
        while (true) {
            val info = viewModel.playerMediaInfo().keepingAudioOf(mediaInfo, audioSelected = selectedAudio != null)
            if (info != mediaInfo) mediaInfo = info
            val release = viewModel.playingReleaseText()
            if (release != releaseText) releaseText = release
            val file = viewModel.playingFileName()
            if (file != fileName) fileName = file
            // Look again sooner while the row is still filling in or the player catches up with a switch;
            // capped, as a stream with no audio never completes and a source's track ids may never meet.
            val switching = info.audioTrackId != null && selectedAudio?.trackId != null && info.audioTrackId != selectedAudio.trackId
            val settling = switching || !info.isComplete || !info.hdrKnown
            delay(if (settling && quickReads++ < MAX_QUICK_READS) QUICK_REFRESH_MS else BADGE_REFRESH_MS)
        }
    }
    if (!enabled) return emptyList()
    // Follow the track the player is playing, which lags the selection for a moment after a switch.
    val playingAudio = playingAudioTrack(uiState.audioTracks, mediaInfo)
    val audioFromRelease = releaseDescribesTrack(uiState.audioTracks, playingAudio)
    if (filters.isEmpty()) {
        return remember(mediaInfo, releaseText, audioFromRelease) {
            builtInPlayerBadges(mediaInfo, releaseText, audioFromRelease).map { PlayerBadge.BuiltIn(it) }
        }
    }
    val language = mediaInfo.audioLanguage ?: playingAudio?.language
    val languageTokens = playbackLanguageTokens(language)
    val playing = playerBadgePlayingText(mediaInfo, languageTokens, releaseText, fileName, audioFromRelease)
    val text = playerBadgeMatchText(mediaInfo, languageTokens, releaseText, fileName, audioFromRelease)
    LaunchedEffect(text) {
        Log.d(TAG, "BADGE_MATCH: \"$text\"")
    }
    return remember(playing, text, filters) {
        StreamBadgeMatcher.matchedPlayerBadges(
            playing.takeIf { it.isNotBlank() },
            listOfNotNull(text.takeIf { it.isNotBlank() }),
            filters
        )
            .filter { it.imageURL.isNotBlank() }
            .map { PlayerBadge.Pack(it) }
    }
}

/** Not focusable; when the row is too wide the last badges drop first (channels, then Atmos). */
@Composable
internal fun PlayerBadges(
    badges: List<PlayerBadge>,
    modifier: Modifier = Modifier
) {
    val gapDp = NuvioTheme.spacing.xs
    Layout(
        content = {
            badges.forEach { badge ->
                when (badge) {
                    is PlayerBadge.Pack -> StreamImportedBadgeChip(badge = badge.badge)
                    is PlayerBadge.BuiltIn -> Image(
                        painter = painterResource(id = badge.res),
                        contentDescription = null,
                        modifier = Modifier.height(PLAYER_BADGE_HEIGHT),
                        contentScale = ContentScale.FillHeight
                    )
                }
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val gap = gapDp.roundToPx()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        var width = 0
        var visibleCount = 0
        for (placeable in placeables) {
            val next = if (visibleCount == 0) placeable.width else width + gap + placeable.width
            if (next > constraints.maxWidth) break
            width = next
            visibleCount++
        }
        val visible = placeables.take(visibleCount)
        val height = visible.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            visible.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gap
            }
        }
    }
}

private const val TAG = "PlayerBadges"
private val PLAYER_BADGE_HEIGHT = 20.dp
private const val BADGE_REFRESH_MS = 1_000L
private const val QUICK_REFRESH_MS = 250L
private const val MAX_QUICK_READS = 40
