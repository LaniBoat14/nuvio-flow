package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** Root bounds of the seek bar on screen, reported by the bar; thumbnails sit just above it. */
internal object SeekBarAnchor {
    var bounds by mutableStateOf<Rect?>(null)
}

private val BarGap = 12.dp

/** The picture slides to a new spot on the bar over this long. */
private const val THUMBNAIL_FOLLOW_MS = 166

/** True while thumbnails are drawn over the seek bar; the title block above the bar steps aside meanwhile. */
internal object SeekThumbnailOverlay {
    var visible by mutableStateOf(false)
}

/** Fades the title block out while thumbnails cover it. */
internal fun Modifier.hideUnderSeekThumbnails(): Modifier = composed {
    val alpha by animateFloatAsState(if (SeekThumbnailOverlay.visible) 0f else 1f, tween(150), label = "titleUnderThumbs")
    graphicsLayer { this.alpha = alpha }
}

/** Seek time and, for a film with chapters, the chapter at that time; sits just above the picture. */
@Composable
private fun SeekThumbnailLabel(chapters: List<PlayerChapter>, positionMs: Long, modifier: Modifier = Modifier) {
    val index = PlayerChapters.indexAt(chapters, positionMs)
    val chapterText = chapters.getOrNull(index)?.let { it.title ?: stringResource(R.string.player_chapter_number, index + 1) }
    val accent = NuvioTheme.colors.Secondary
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    Text(
        text = buildAnnotatedString {
            append(formatSeekTime(positionMs))
            if (chapterText != null) {
                append(" · ")
                withStyle(SpanStyle(color = accent)) { append(chapterText) }
            }
        },
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 560.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
    )
}

/** The paused hint keeps the screen on only while its percentage still moves. */
private const val KEEP_SCREEN_ON_STALL_MS = 5L * 60 * 1000

/** Lays [content] out over the seek bar's width, bottom edge [BarGap] above the bar. */
@Composable
private fun AboveSeekBar(
    modifier: Modifier,
    anchored: Boolean = true,
    contentAlignment: Alignment = Alignment.BottomStart,
    content: @Composable BoxScope.(width: Dp) -> Unit
) {
    val bar = SeekBarAnchor.bounds?.takeIf { anchored && it.width > 0f }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val start: Dp
            val end: Dp
            val bottom: Dp
            if (bar != null) {
                with(density) {
                    start = bar.left.coerceAtLeast(0f).toDp()
                    end = (constraints.maxWidth - bar.right).coerceAtLeast(0f).toDp()
                    bottom = (constraints.maxHeight - bar.top).coerceAtLeast(0f).toDp() + BarGap
                }
            } else {
                start = NuvioTheme.spacing.xxl
                end = NuvioTheme.spacing.xxl
                bottom = 140.dp
            }
            val width = (maxWidth - start - end).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier.fillMaxSize().padding(PaddingValues(start = start, end = end, bottom = bottom))
                    .clipToBounds(),
                contentAlignment = contentAlignment
            ) {
                content(width)
            }
        }
    }
}

internal fun seekThumbnailPainter(shown: SeekThumbnails.Shown?): BitmapPainter? = croppedPainter(shown)

private fun croppedPainter(shown: SeekThumbnails.Shown?): BitmapPainter? {
    val bitmap = shown?.bitmap ?: return null
    val crop = shown.crop?.takeIf { it.fits(bitmap.width, bitmap.height) }
    val size = IntSize(
        (bitmap.width - (crop?.let { it.left + it.right } ?: 0)).coerceAtLeast(1),
        (bitmap.height - (crop?.let { it.top + it.bottom } ?: 0)).coerceAtLeast(1)
    )
    return BitmapPainter(bitmap.asImageBitmap(), IntOffset(crop?.left ?: 0, crop?.top ?: 0), size)
}

/**
 * The thumbnail above the seek bar while a seek key is held. It follows the preview position and sits over the
 * bar at the matching spot. A tap seeks at once and shows nothing.
 */
@Composable
fun SeekThumbnailOverlayHost(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val timeline by viewModel.playbackTimeline.collectAsState()
    val tick by SeekThumbnails.tick
    val seekPositionMs = uiState.pendingPreviewSeekPosition
    val durationMs = timeline.duration
    DisposableEffect(Unit) { onDispose { SeekThumbnailOverlay.visible = false } }
    val fraction by animateFloatAsState(
        targetValue = if (durationMs > 0L) ((seekPositionMs ?: timeline.currentPosition).toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(THUMBNAIL_FOLLOW_MS, easing = LinearEasing),
        label = "thumbFraction"
    )
    if (seekPositionMs == null || durationMs <= 0L) {
        SideEffect { SeekThumbnailOverlay.visible = false }
        return
    }
    val painter = remember(seekPositionMs, tick) { croppedPainter(SeekThumbnails.shownFor(seekPositionMs)) }
    // One frame shape for every picture of the title, so the frame never changes width from one picture to the next.
    val aspect = SeekThumbnails.frameAspect() ?: painter?.intrinsicSize?.let { it.width / it.height }
    if (aspect == null) {
        SideEffect { SeekThumbnailOverlay.visible = false }
        return
    }
    SideEffect { SeekThumbnailOverlay.visible = true }
    val height = 108.dp
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    AboveSeekBar(modifier, contentAlignment = BiasAlignment(horizontalBias = fraction * 2f - 1f, verticalBias = 1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SeekThumbnailLabel(uiState.chapters, seekPositionMs, Modifier.padding(bottom = NuvioTheme.spacing.sm))
            Box(
                Modifier.height(height).width(height * aspect)
                    .border(NuvioTheme.focusRing.border(2.dp), shape)
                    .clip(shape)
                    .background(Color.Black.copy(alpha = 0.85f))
            ) {
                if (painter != null) {
                    Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** True while a side panel, menu or the details overlay covers the screen the progress hint would sit on. */
internal fun seekThumbnailHintCovered(state: PlayerUiState): Boolean =
    state.showPauseOverlay || state.showSourcesPanel || state.showEpisodesPanel || state.showChaptersPanel ||
        state.showAudioOverlay || state.showSubtitleOverlay || state.showSubtitleStylePanel ||
        state.showSubtitleTimingDialog || state.showSubtitleDelayOverlay || state.showSpeedDialog ||
        state.showMoreDialog || state.showStreamInfoOverlay || state.showPartyPanel ||
        state.postPlayMode != null

/** While paused on a small box, shows how far a 4K title's thumbnails are. */
@Composable
fun SeekThumbnailProgressHint(
    uiState: PlayerUiState,
    modifier: Modifier = Modifier,
    covered: Boolean = false
) {
    val tick by SeekThumbnails.tick
    val paused = !uiState.isPlaying && !uiState.isBuffering && uiState.pendingPreviewSeekPosition == null &&
        uiState.error == null
    val percent = remember(tick, paused) { if (paused) SeekThumbnails.progressPercent() else null } ?: return
    // The screensaver counts as away and would stop the paused pass; the screen stays on while it still counts.
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(percent) {
        stalled = false
        delay(KEEP_SCREEN_ON_STALL_MS)
        stalled = true
    }
    if (!stalled) {
        val view = LocalView.current
        DisposableEffect(view) {
            val before = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = before }
        }
    }
    if (covered || seekThumbnailHintCovered(uiState)) return
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    AboveSeekBar(modifier, anchored = uiState.showControls, contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.seek_thumbnails_progress_chip, percent),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}
