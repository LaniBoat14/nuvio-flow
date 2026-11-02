package com.nuvio.tv.core.calendar

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TvdbApi
import com.nuvio.tv.data.remote.dto.TvdbAirsDaysDto
import com.nuvio.tv.data.remote.dto.TvdbLoginRequestDto
import com.nuvio.tv.data.remote.dto.TvdbSeriesDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.Response

/** A show's regular slot on TheTVDB: [time] on [days] in [zone], and the date of the next episode when known. */
data class TvdbAirs(
    val time: LocalTime,
    val zone: ZoneId,
    val days: Set<DayOfWeek> = emptySet(),
    val nextAired: LocalDate? = null
)

@Serializable
data class TvdbCachedId(val id: Long? = null, val checkedAtMs: Long = 0L)

@Serializable
data class TvdbCachedShow(
    val time: String? = null,
    val zone: String? = null,
    val days: List<Int> = emptyList(),
    val nextAired: String? = null,
    val fetchedAtMs: Long = 0L
)

@Serializable
data class TvdbCache(
    val token: String? = null,
    val tokenExpiresAtMs: Long = 0L,
    val ids: Map<String, TvdbCachedId> = emptyMap(),
    val shows: Map<String, TvdbCachedShow> = emptyMap()
)

class TvdbCacheFile(private val file: () -> File) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    suspend fun load(): TvdbCache = withContext(Dispatchers.IO) {
        try {
            val target = file()
            if (target.exists()) json.decodeFromString(TvdbCache.serializer(), target.readText()) else TvdbCache()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TvdbCache()
        }
    }

    suspend fun save(cache: TvdbCache) = withContext(Dispatchers.IO) {
        try {
            val target = file()
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(TvdbCache.serializer(), cache))
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "TheTVDB cache not saved: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "CalendarTvdb"
    }
}

/**
 * TheTVDB v4, only for air times. Off when no key is built in. The token is kept for [TOKEN_LIFETIME] and renewed
 * early; ids for [ID_RECHECK] (not found: [NOT_FOUND_RECHECK]); times for [TIME_RECHECK]. A failed call keeps the old
 * time when there is one and pauses TheTVDB for [FAILURE_PAUSE].
 */
@Singleton
class TvdbCalendarClient internal constructor(
    private val api: TvdbApi,
    private val apiKey: String,
    private val cacheFile: TvdbCacheFile,
    private val limiter: CalendarRateLimiter,
    private val nowMs: () -> Long
) {
    @Inject
    constructor(api: TvdbApi, @ApplicationContext context: Context) : this(
        api,
        BuildConfig.TVDB_API_KEY,
        TvdbCacheFile { File(context.filesDir, "tvdb/cache.json") },
        CalendarRateLimiter(limit = 10, burst = 3, nowMs = SystemClock::elapsedRealtime),
        System::currentTimeMillis
    )

    private val mutex = Mutex()
    private var cache: TvdbCache? = null
    private var pausedUntilMs = 0L

    val enabled: Boolean get() = apiKey.isNotBlank()

    /** Null when the key is missing or TheTVDB has no regular time; throws when it cannot be reached and nothing is kept. */
    suspend fun airs(ids: CalendarIds): TvdbAirs? {
        if (!enabled) return null
        return mutex.withLock {
            val now = nowMs()
            val current = cache ?: cacheFile.load().also { cache = it }
            val match = seriesId(ids, current, now) ?: return@withLock null
            val id = match.id
            val unconfirmed = match.tmdbAlias != null
            val kept = cache?.shows?.get(id.toString())
            if (!unconfirmed && kept != null && now - kept.fetchedAtMs < TIME_RECHECK.toMillis()) return@withLock kept.toAirs()
            if (now < pausedUntilMs) {
                if (unconfirmed) throw CalendarFetchException("TheTVDB paused")
                return@withLock kept?.toAirs() ?: throw CalendarFetchException("TheTVDB paused")
            }
            val series = try {
                authorized { auth -> api.seriesExtended(auth, id) }.foundOrNull()?.data
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                pausedUntilMs = now + FAILURE_PAUSE.toMillis()
                if (kept != null && !unconfirmed) return@withLock kept.toAirs()
                throw error
            }
            match.tmdbAlias?.let { alias ->
                // TheTVDB's id search is not limited to TMDB: the same number can belong to another site's show.
                val confirmed = tvdbConfirmsTmdb(series, ids.tmdb)
                update { it.copy(ids = it.ids + (alias to TvdbCachedId(id.takeIf { confirmed }, now))) }
                if (!confirmed) return@withLock null
            }
            val show = tvdbCachedShow(series, now)
            update { it.copy(shows = it.shows + (id.toString() to show)) }
            show.toAirs()
        }
    }

    /** [tmdbAlias] is set when the id was just found from the TMDB number alone and TheTVDB has not confirmed it. */
    private class SeriesMatch(val id: Long, val tmdbAlias: String? = null)

    private suspend fun seriesId(ids: CalendarIds, current: TvdbCache, now: Long): SeriesMatch? {
        ids.tvdb?.let { return SeriesMatch(it) }
        val remoteIds = listOfNotNull(ids.imdb, ids.tmdb?.toString())
        for (remote in remoteIds) {
            val alias = if (remote.startsWith("tt")) "imdb:$remote" else "tmdb:$remote"
            val known = current.ids[alias]
            if (known != null) {
                val recheck = if (known.id == null) NOT_FOUND_RECHECK else ID_RECHECK
                if (now - known.checkedAtMs < recheck.toMillis()) {
                    if (known.id != null) return SeriesMatch(known.id)
                    continue
                }
            }
            if (now < pausedUntilMs) return known?.id?.let(::SeriesMatch) ?: throw CalendarFetchException("TheTVDB paused")
            val found = try {
                val results = authorized { auth -> api.searchRemoteId(auth, remote) }.foundOrNull()?.data.orEmpty()
                results.mapNotNull { it.series?.id }.distinct().singleOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                pausedUntilMs = now + FAILURE_PAUSE.toMillis()
                return known?.id?.let(::SeriesMatch) ?: throw error
            }
            val byImdb = remote.startsWith("tt")
            if (found == null || byImdb) update { it.copy(ids = it.ids + (alias to TvdbCachedId(found, now))) }
            if (found != null) return SeriesMatch(found, alias.takeUnless { byImdb })
        }
        return null
    }

    private suspend fun <T> authorized(block: suspend (String) -> Response<T>): Response<T> {
        val first = call { block("Bearer ${token(forceNew = false)}") }
        if (first.code() != 401) return first
        return call { block("Bearer ${token(forceNew = true)}") }
    }

    private suspend fun token(forceNew: Boolean): String {
        val now = nowMs()
        val current = cache ?: TvdbCache()
        val kept = current.token
        if (!forceNew && kept != null && current.tokenExpiresAtMs - now > TOKEN_RENEW_BEFORE.toMillis()) return kept
        val response = call { api.login(TvdbLoginRequestDto(apiKey)) }
        val fresh = response.body()?.data?.token?.takeIf { response.isSuccessful && it.isNotBlank() }
            ?: throw CalendarFetchException("TheTVDB login ${response.code()}")
        update { it.copy(token = fresh, tokenExpiresAtMs = now + TOKEN_LIFETIME.toMillis()) }
        return fresh
    }

    private suspend fun <T> call(block: suspend () -> Response<T>): Response<T> {
        repeat(MAX_ATTEMPTS) {
            limiter.acquire()
            val response = block()
            if (response.code() != 429) return response
            limiter.pauseFor(parseRetryAfterMs(response.headers()["Retry-After"]) ?: DEFAULT_RETRY_MS)
        }
        throw CalendarFetchException("TheTVDB rate limit")
    }

    private fun <T> Response<T>.foundOrNull(): T? = when {
        isSuccessful -> body()
        code() == 404 -> null
        else -> throw CalendarFetchException("TheTVDB ${code()}")
    }

    private suspend fun update(change: (TvdbCache) -> TvdbCache) {
        val updated = prunedTvdbCache(change(cache ?: TvdbCache()), nowMs())
        cache = updated
        cacheFile.save(updated)
    }

    internal companion object {
        const val MAX_ATTEMPTS = 3
        const val DEFAULT_RETRY_MS = 10_000L
        val TOKEN_LIFETIME: Duration = Duration.ofDays(28)
        val TOKEN_RENEW_BEFORE: Duration = Duration.ofDays(3)
        val TIME_RECHECK: Duration = Duration.ofHours(6)
        val ID_RECHECK: Duration = Duration.ofDays(90)
        val NOT_FOUND_RECHECK: Duration = Duration.ofDays(7)
        val FAILURE_PAUSE: Duration = Duration.ofMinutes(15)
    }
}

private const val TVDB_CACHE_MAX_ROWS = 2_000
private val TVDB_CACHE_KEEP: Duration = Duration.ofDays(120)

fun prunedTvdbCache(cache: TvdbCache, nowMs: Long): TvdbCache {
    val from = nowMs - TVDB_CACHE_KEEP.toMillis()
    return cache.copy(
        ids = cache.ids.filterValues { it.checkedAtMs >= from }.entries
            .sortedByDescending { it.value.checkedAtMs }.take(TVDB_CACHE_MAX_ROWS).associate { it.key to it.value },
        shows = cache.shows.filterValues { it.fetchedAtMs >= from }.entries
            .sortedByDescending { it.value.fetchedAtMs }.take(TVDB_CACHE_MAX_ROWS).associate { it.key to it.value }
    )
}

/** True when the show lists [tmdb] as its TMDB id, or when TheTVDB sent no remote ids to check against. */
fun tvdbConfirmsTmdb(series: TvdbSeriesDto?, tmdb: Long?): Boolean {
    val remoteIds = series?.remoteIds ?: return true
    return tmdb != null && remoteIds.any { remote ->
        (remote.type == TVDB_SOURCE_TMDB || remote.sourceName?.contains("TheMovieDB", ignoreCase = true) == true) &&
            remote.id?.trim() == tmdb.toString()
    }
}

private const val TVDB_SOURCE_TMDB = 12

fun tvdbCachedShow(series: TvdbSeriesDto?, nowMs: Long): TvdbCachedShow {
    val time = series?.airsTime?.let(::parseTvdbAirsTime)
    val zone = series?.let { listOf(it.originalCountry, it.originalNetwork?.country, it.latestNetwork?.country, it.country) }
        ?.firstNotNullOfOrNull(::tvdbCountryZone)
    return TvdbCachedShow(
        time = time?.toString(),
        zone = zone?.id,
        days = series?.airsDays?.let(::tvdbAirsDays).orEmpty().map(DayOfWeek::getValue).sorted(),
        nextAired = series?.nextAired?.trim()?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess },
        fetchedAtMs = nowMs
    )
}

fun TvdbCachedShow.toAirs(): TvdbAirs? {
    val parsedTime = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
    val parsedZone = zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
    return TvdbAirs(
        time = parsedTime,
        zone = parsedZone,
        days = days.mapNotNullTo(HashSet()) { day -> runCatching { DayOfWeek.of(day) }.getOrNull() },
        nextAired = nextAired?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    )
}

/** "20:00", "8:00 PM" or "20:00:00"; null when blank or unreadable. */
fun parseTvdbAirsTime(raw: String): LocalTime? {
    val value = raw.trim().uppercase()
    if (value.isEmpty()) return null
    val pm = value.endsWith("PM")
    val am = value.endsWith("AM")
    val digits = value.removeSuffix("PM").removeSuffix("AM").trim()
    val parts = digits.split(':')
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val hour24 = when {
        pm && hour in 1..11 -> hour + 12
        am && hour == 12 -> 0
        else -> hour
    }
    if (hour24 !in 0..23 || minute !in 0..59) return null
    return LocalTime.of(hour24, minute)
}

fun tvdbAirsDays(days: TvdbAirsDaysDto): Set<DayOfWeek> = buildSet {
    if (days.monday == true) add(DayOfWeek.MONDAY)
    if (days.tuesday == true) add(DayOfWeek.TUESDAY)
    if (days.wednesday == true) add(DayOfWeek.WEDNESDAY)
    if (days.thursday == true) add(DayOfWeek.THURSDAY)
    if (days.friday == true) add(DayOfWeek.FRIDAY)
    if (days.saturday == true) add(DayOfWeek.SATURDAY)
    if (days.sunday == true) add(DayOfWeek.SUNDAY)
}

/**
 * TheTVDB gives US times in US Eastern and other countries' times in the capital or largest city. Countries not listed
 * get no time, so the calendar falls back to Trakt rather than guess.
 */
fun tvdbCountryZone(country: String?): ZoneId? {
    val code = country?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    return TVDB_COUNTRY_ZONES[code]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
}

private val TVDB_COUNTRY_ZONES = mapOf(
    "usa" to "America/New_York", "gbr" to "Europe/London", "can" to "America/Toronto", "aus" to "Australia/Sydney",
    "nzl" to "Pacific/Auckland", "irl" to "Europe/Dublin", "deu" to "Europe/Berlin", "fra" to "Europe/Paris",
    "esp" to "Europe/Madrid", "ita" to "Europe/Rome", "nld" to "Europe/Amsterdam", "bel" to "Europe/Brussels",
    "lux" to "Europe/Luxembourg", "che" to "Europe/Zurich", "aut" to "Europe/Vienna", "swe" to "Europe/Stockholm",
    "nor" to "Europe/Oslo", "dnk" to "Europe/Copenhagen", "fin" to "Europe/Helsinki", "isl" to "Atlantic/Reykjavik",
    "prt" to "Europe/Lisbon", "pol" to "Europe/Warsaw", "cze" to "Europe/Prague", "svk" to "Europe/Bratislava",
    "hun" to "Europe/Budapest", "rou" to "Europe/Bucharest", "bgr" to "Europe/Sofia", "grc" to "Europe/Athens",
    "hrv" to "Europe/Zagreb", "srb" to "Europe/Belgrade", "svn" to "Europe/Ljubljana", "ukr" to "Europe/Kiev",
    "rus" to "Europe/Moscow", "tur" to "Europe/Istanbul", "isr" to "Asia/Jerusalem", "egy" to "Africa/Cairo",
    "zaf" to "Africa/Johannesburg", "nga" to "Africa/Lagos", "sau" to "Asia/Riyadh", "are" to "Asia/Dubai",
    "ind" to "Asia/Kolkata", "pak" to "Asia/Karachi", "jpn" to "Asia/Tokyo", "kor" to "Asia/Seoul",
    "chn" to "Asia/Shanghai", "twn" to "Asia/Taipei", "hkg" to "Asia/Hong_Kong", "tha" to "Asia/Bangkok",
    "vnm" to "Asia/Ho_Chi_Minh", "phl" to "Asia/Manila", "idn" to "Asia/Jakarta", "mys" to "Asia/Kuala_Lumpur",
    "sgp" to "Asia/Singapore", "bra" to "America/Sao_Paulo", "mex" to "America/Mexico_City",
    "arg" to "America/Argentina/Buenos_Aires", "col" to "America/Bogota", "chl" to "America/Santiago",
    "per" to "America/Lima"
)

/**
 * TheTVDB's time for each date-only episode still to come, under the episode's own number so it merges with the date:
 * only when the date falls on one of the show's air days, or is TheTVDB's own next episode date. A date a day off
 * TheTVDB's would get TheTVDB's time on the wrong day, 24 h out.
 */
fun calendarTvdbTimes(entries: List<CalendarEntry>, airs: TvdbAirs, now: Instant): List<CalendarEntry> =
    entries.mapNotNull { entry ->
        if (entry.kind != CalendarEntryKind.EPISODE || entry.timeKnown || entry.isOut(now)) return@mapNotNull null
        val date = entry.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
        if (!tvdbAirsOn(airs, date)) return@mapNotNull null
        entry.copy(
            airInstantMs = date.atTime(airs.time).atZone(airs.zone).toInstant().toEpochMilli(),
            airDate = null,
            source = CalendarSource.TVDB
        )
    }

fun tvdbAirsOn(airs: TvdbAirs, date: LocalDate): Boolean =
    date.dayOfWeek in airs.days || airs.nextAired == date
