package com.nuvio.tv.core.calendar

import com.nuvio.tv.domain.model.Meta
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Posters for titles none of their sources gave one, by id alias. The addon's own poster comes first when the
 * title's details are already in memory, as on Home, then the one Continue Watching saved. Nothing is fetched.
 */
fun calendarCachedPosters(
    candidates: List<CalendarTitleCandidate>,
    metaPoster: (type: String, id: String) -> String?,
    nextUpPosters: List<Pair<String, String?>>
): Map<String, String> {
    val missing = candidates.filter { it.poster.isNullOrBlank() }
    if (missing.isEmpty()) return emptyMap()
    val wanted = missing.flatMapTo(HashSet()) { it.ids.aliases() }
    val posters = HashMap<String, String>()
    nextUpPosters.forEach { (contentId, poster) ->
        val url = poster?.takeIf(String::isNotBlank) ?: return@forEach
        CalendarIds.fromContentId(contentId).aliases().filter { it in wanted }.forEach { posters.putIfAbsent(it, url) }
    }
    missing.forEach { candidate ->
        val type = if (candidate.type == CalendarTitleType.MOVIE) "movie" else "series"
        val url = listOfNotNull(candidate.contentId, candidate.ids.imdb, candidate.ids.tmdb?.let { "tmdb:$it" })
            .distinct()
            .firstNotNullOfOrNull { id -> metaPoster(type, id)?.takeIf(String::isNotBlank) }
            ?: return@forEach
        candidate.ids.aliases().forEach { posters[it] = url }
    }
    return posters
}

fun List<CalendarFollowedTitle>.withCachedPosters(posters: Map<String, String>): List<CalendarFollowedTitle> =
    if (posters.isEmpty()) this else map { title ->
        if (!title.poster.isNullOrBlank()) return@map title
        title.ids.aliases().firstNotNullOfOrNull(posters::get)?.let { title.copy(poster = it) } ?: title
    }

data class CalendarArt(
    val backdrop: String?,
    val logo: String? = null,
    val rating: Double? = null,
    val genre: String? = null
)

fun calendarArtOf(meta: Meta): CalendarArt = CalendarArt(
    backdrop = meta.background,
    logo = meta.logo,
    rating = meta.imdbRating?.toDouble()?.takeIf { it > 0 },
    genre = meta.genres.firstOrNull { it.isNotBlank() }
)

val CALENDAR_BACKDROP_RECHECK: Duration = Duration.ofDays(1)
val CALENDAR_ART_REFRESH: Duration = Duration.ofDays(7)
const val CALENDAR_BACKDROP_FETCH_MAX = 40

/**
 * Backdrops by id alias: the addon's own when the title's details are already in memory, then the one Continue
 * Watching saved. Nothing is fetched.
 */
fun calendarCachedArt(
    candidates: List<CalendarTitleCandidate>,
    metaArt: (type: String, id: String) -> CalendarArt?,
    continueWatchingArt: List<Pair<String, CalendarArt>>
): Map<String, CalendarArt> {
    if (candidates.isEmpty()) return emptyMap()
    val wanted = candidates.flatMapTo(HashSet()) { it.ids.aliases() }
    val art = HashMap<String, CalendarArt>()
    continueWatchingArt.forEach { (contentId, item) ->
        if (item.backdrop.isNullOrBlank()) return@forEach
        CalendarIds.fromContentId(contentId).aliases().filter { it in wanted }.forEach { art.putIfAbsent(it, item) }
    }
    candidates.forEach { candidate ->
        val type = if (candidate.type == CalendarTitleType.MOVIE) "movie" else "series"
        val found = listOfNotNull(candidate.contentId, candidate.ids.imdb, candidate.ids.tmdb?.let { "tmdb:$it" })
            .distinct()
            .firstNotNullOfOrNull { id -> metaArt(type, id)?.takeIf { !it.backdrop.isNullOrBlank() } }
            ?: return@forEach
        candidate.ids.aliases().forEach { art[it] = found }
    }
    return art
}

/** A title takes the backdrop the app holds now; one the app holds nothing for keeps what it saved. */
fun List<CalendarTitleRecord>.withCachedArt(art: Map<String, CalendarArt>): List<CalendarTitleRecord> =
    if (art.isEmpty()) this else map { record ->
        val found = record.ids.aliases().firstNotNullOfOrNull(art::get) ?: return@map record
        val logo = found.logo?.takeIf(String::isNotBlank) ?: record.logo
        val rating = found.rating ?: record.rating
        val genre = found.genre?.takeIf(String::isNotBlank) ?: record.genre
        if (found.backdrop == record.backdrop && logo == record.logo && rating == record.rating && genre == record.genre) record
        else record.copy(backdrop = found.backdrop, logo = logo, rating = rating, genre = genre)
    }

/**
 * Every followed title with a date whose details are due, soonest release first, at most [max]. A title without a
 * backdrop is tried again a day after its last try; one with everything is read again after a week for its rating.
 */
fun calendarBackdropsWanted(
    titles: List<CalendarTitleRecord>,
    now: Instant,
    max: Int = CALENDAR_BACKDROP_FETCH_MAX
): List<CalendarTitleRecord> {
    val nowMs = now.toEpochMilli()
    return titles.asSequence()
        .filter { it.entries.isNotEmpty() }
        .filter { title ->
            val wait = if (title.backdrop.isNullOrBlank() || title.backdropCheckedAtMs == 0L) CALENDAR_BACKDROP_RECHECK else CALENDAR_ART_REFRESH
            nowMs - title.backdropCheckedAtMs >= wait.toMillis()
        }
        .sortedBy { title -> title.entries.mapNotNull { it.sortInstant()?.toEpochMilli() }.minOfOrNull { abs(it - nowMs) } ?: Long.MAX_VALUE }
        .take(max)
        .toList()
}

/** The lookup is noted whatever it found, so the next one waits; what it did not find keeps what was saved. */
fun CalendarTitleRecord.withFetchedArt(art: CalendarArt?, now: Instant): CalendarTitleRecord = copy(
    backdrop = art?.backdrop?.takeIf(String::isNotBlank) ?: backdrop,
    logo = art?.logo?.takeIf(String::isNotBlank) ?: logo,
    rating = art?.rating?.takeIf { it > 0 } ?: rating,
    genre = art?.genre?.takeIf(String::isNotBlank) ?: genre,
    backdropCheckedAtMs = now.toEpochMilli()
)
