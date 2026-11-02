package com.nuvio.tv.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.MonochromePosterPlaceholder
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A landscape picture: the first of [url], [alternate] and [fallback] that loads; one that fails hands over to the
 * next, so a missing episode picture shows the backdrop and then the poster.
 */
@Composable
internal fun CalendarArtImage(
    url: String?,
    modifier: Modifier = Modifier,
    fallback: String? = null,
    alternate: String? = null,
    alignment: Alignment = Alignment.Center
) {
    val context = LocalContext.current
    val candidates = remember(url, alternate, fallback) {
        listOfNotNull(url, alternate, fallback).filter(String::isNotBlank).distinct()
    }
    var index by remember(candidates) { mutableStateOf(0) }
    val chosen = candidates.getOrNull(index)
    Box(modifier.background(NuvioTheme.colors.BackgroundCard)) {
        if (chosen == null) {
            MonochromePosterPlaceholder()
            return@Box
        }
        val request = remember(chosen) { ImageRequest.Builder(context).data(chosen).crossfade(false).build() }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = alignment,
            onError = { index++ },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** The show's logo, or its name when there is no logo or it does not load. */
@Composable
internal fun CalendarLogo(
    logo: String?,
    name: String?,
    height: Dp,
    maxWidth: Dp,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomStart
) {
    var failed by remember(logo) { mutableStateOf(false) }
    val title = name ?: stringResource(R.string.calendar_unknown_title)
    if (logo.isNullOrBlank() || failed) {
        Text(
            text = title,
            style = textStyle,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.widthIn(max = maxWidth)
        )
        return
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    val request = remember(logo, heightPx) {
        ImageRequest.Builder(context).data(logo).size(heightPx * 12, heightPx * 2).crossfade(false).build()
    }
    Box(modifier.height(height).widthIn(max = maxWidth)) {
        AsyncImage(
            model = request,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = alignment,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Card and text sizes, on the app's own font. */
internal object CalendarText {
    private fun TextStyle.sized(size: Int, weight: FontWeight, line: Float = 1.3f) =
        copy(fontSize = size.sp, fontWeight = weight, lineHeight = (size * line).sp, letterSpacing = 0.1.sp)

    val heroEpisode: TextStyle @Composable get() = MaterialTheme.typography.titleLarge.sized(17, FontWeight.SemiBold)
    val heroMeta: TextStyle @Composable get() = MaterialTheme.typography.bodyLarge.sized(13, FontWeight.Normal)
    val heroSummary: TextStyle @Composable get() = MaterialTheme.typography.bodyMedium.sized(13, FontWeight.Normal, 1.45f)
    val dayHeading: TextStyle @Composable get() = MaterialTheme.typography.titleMedium.sized(16, FontWeight.Medium)
    val dayHint: TextStyle @Composable get() = MaterialTheme.typography.bodyMedium.sized(13, FontWeight.Normal)
    val cardTitle: TextStyle @Composable get() = MaterialTheme.typography.bodyMedium.sized(13, FontWeight.Normal)
    val cardSub: TextStyle @Composable get() = MaterialTheme.typography.bodySmall.sized(11, FontWeight.Normal)
    val badge: TextStyle @Composable get() = MaterialTheme.typography.labelLarge.sized(12, FontWeight.SemiBold)
    val stripDay: TextStyle @Composable get() = MaterialTheme.typography.labelMedium.sized(10, FontWeight.Normal)
    val stripNumber: TextStyle @Composable get() = MaterialTheme.typography.titleLarge.sized(15, FontWeight.SemiBold)
    val pill: TextStyle @Composable get() = MaterialTheme.typography.titleSmall.sized(14, FontWeight.Medium)
}

/** A dark fade from the bottom of a picture so the logo and badges stay readable. */
internal fun calendarArtScrim(): Brush =
    Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))

@Composable
internal fun CalendarBadge(text: String, modifier: Modifier = Modifier, accent: Boolean = false, style: TextStyle = CalendarText.badge) {
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    Text(
        text = text,
        style = style,
        fontWeight = FontWeight.SemiBold,
        color = if (accent) NuvioTheme.colors.OnSecondary else Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(shape)
            .background(if (accent) NuvioTheme.colors.Secondary else Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** "S2 E8", "S2 E1-E3" for a run, nothing for films. */
@Composable
internal fun calendarCardCode(card: CalendarCard): String? {
    val row = card.row
    if (row.kind != CalendarRowKind.EPISODE || row.season == null || row.episode == null) return null
    val last = card.lastEpisode
    return if (last != null && last != row.episode) {
        stringResource(R.string.calendar_episode_range, row.season, row.episode, last)
    } else stringResource(R.string.calendar_episode_code, row.season, row.episode)
}

/** Under a card: the episode's title, "3 episodes" for a run, or the film's release kind. */
@Composable
internal fun calendarCardTitle(card: CalendarCard): String {
    val row = card.row
    if (card.count > 1 && row.kind == CalendarRowKind.EPISODE) {
        return pluralStringResource(R.plurals.calendar_month_episodes, card.count, card.count)
    }
    return when (row.kind) {
        CalendarRowKind.EPISODE -> row.episodeTitle ?: row.season?.let { s ->
            row.episode?.let { stringResource(R.string.calendar_episode_code, s, it) }
        }.orEmpty()
        else -> calendarRowLine(row)
    }
}

/** "★ 8.4 · Drama"; null when neither is known. */
@Composable
internal fun calendarRatingGenre(rating: Double?, genre: String?): String? {
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    val score = rating?.takeIf { it > 0 }?.let { "★ " + String.format(locale, "%.1f", it) }
    val parts = listOfNotNull(score, genre?.takeIf(String::isNotBlank))
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
