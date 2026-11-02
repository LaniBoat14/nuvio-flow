package com.nuvio.tv.core.calendar

import com.nuvio.tv.data.remote.api.TvdbApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class TvdbCalendarClientTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: TvdbApi
    private lateinit var cacheFile: File
    private var wallMs = Instant.parse("2026-10-24T10:00:00Z").toEpochMilli()
    private var limiterMs = 0L

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/v4/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TvdbApi::class.java)
        cacheFile = File(folder.root, "tvdb/cache.json")
    }

    @After fun tearDown() = server.shutdown()

    private fun client(key: String = "test-key") = TvdbCalendarClient(
        api,
        key,
        TvdbCacheFile { cacheFile },
        CalendarRateLimiter(limit = 10, burst = 3, nowMs = { limiterMs }, sleep = { limiterMs += it }),
        { wallMs }
    )

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test fun noKeyMeansNoCallsAndNoTime() = runBlocking {
        val off = client(key = " ")
        assertFalse(off.enabled)
        assertNull(off.airs(CalendarIds(imdb = "tt4574334", tvdb = 305288)))
        assertEquals(0, server.requestCount)
    }

    @Test fun logsInOnceThenReadsTheShowByItsTvdbId() = runBlocking {
        server.enqueue(json(LOGIN))
        server.enqueue(json(STRANGER_THINGS))
        val airs = client().airs(CalendarIds(imdb = "tt4574334", tvdb = 305288))
        assertEquals(TvdbAirs(LocalTime.of(20, 0), ZoneId.of("America/New_York"), setOf(DayOfWeek.WEDNESDAY)), airs)
        val login = server.takeRequest()
        assertEquals("/v4/login", login.path)
        assertEquals("{\"apikey\":\"test-key\"}", login.body.readUtf8())
        val show = server.takeRequest()
        assertEquals("/v4/series/305288/extended?short=true", show.path)
        assertEquals("Bearer token-1", show.getHeader("Authorization"))
    }

    @Test fun imdbThenTmdbLookupAndTheIdIsRemembered() = runBlocking {
        server.enqueue(json(LOGIN))
        server.enqueue(json("""{"status":"success","data":[]}"""))
        server.enqueue(json(REMOTE_ID_SERIES))
        server.enqueue(json(STRANGER_THINGS))
        val first = client()
        assertEquals(LocalTime.of(20, 0), first.airs(CalendarIds(imdb = "tt4574334", tmdb = 66732))?.time)
        server.takeRequest()
        assertEquals("/v4/search/remoteid/tt4574334", server.takeRequest().path)
        assertEquals("/v4/search/remoteid/66732", server.takeRequest().path)
        server.takeRequest()

        wallMs += Duration.ofHours(7).toMillis()
        server.enqueue(json(STRANGER_THINGS))
        assertEquals(LocalTime.of(20, 0), client().airs(CalendarIds(imdb = "tt4574334", tmdb = 66732))?.time)
        assertEquals("/v4/series/305288/extended?short=true", server.takeRequest().path)
        assertEquals(5, server.requestCount)
    }

    @Test fun aTmdbNumberThatBelongsToAnotherSitesShowIsNotUsed() = runBlocking {
        val otherSite = STRANGER_THINGS.replace("\"episodes\":[]", "\"episodes\":[],\"remoteIds\":[{\"id\":\"66732\",\"type\":4,\"sourceName\":\"TV.com\"}]")
        server.enqueue(json(LOGIN))
        server.enqueue(json(REMOTE_ID_SERIES))
        server.enqueue(json(otherSite))
        assertNull(client().airs(CalendarIds(tmdb = 66732)))
        // Remembered as not found, so the next look does not search again.
        assertNull(client().airs(CalendarIds(tmdb = 66732)))
        assertEquals(3, server.requestCount)
    }

    @Test fun aTmdbNumberTheShowListsAsItsTmdbIdIsUsed() = runBlocking {
        val confirmed = STRANGER_THINGS.replace(
            "\"episodes\":[]",
            "\"episodes\":[],\"remoteIds\":[{\"id\":\"tt4574334\",\"type\":2,\"sourceName\":\"IMDB\"}," +
                "{\"id\":\"66732\",\"type\":12,\"sourceName\":\"TheMovieDB.com\"}]"
        )
        server.enqueue(json(LOGIN))
        server.enqueue(json(REMOTE_ID_SERIES))
        server.enqueue(json(confirmed))
        assertEquals(LocalTime.of(20, 0), client().airs(CalendarIds(tmdb = 66732))?.time)
        assertEquals(LocalTime.of(20, 0), client().airs(CalendarIds(tmdb = 66732))?.time)
        assertEquals(3, server.requestCount)
    }

    @Test fun timesAreKeptForSixHoursAndTheTokenSurvivesARestart() = runBlocking {
        server.enqueue(json(LOGIN))
        server.enqueue(json(STRANGER_THINGS))
        val ids = CalendarIds(tvdb = 305288)
        client().airs(ids)
        wallMs += Duration.ofHours(2).toMillis()
        assertEquals(LocalTime.of(20, 0), client().airs(ids)?.time)
        assertEquals(2, server.requestCount)

        wallMs += Duration.ofHours(5).toMillis()
        server.enqueue(json(STRANGER_THINGS))
        client().airs(ids)
        assertEquals(3, server.requestCount)
        server.takeRequest()
        server.takeRequest()
        assertEquals("Bearer token-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun theTokenIsRenewedBeforeItRunsOut() = runBlocking {
        server.enqueue(json(LOGIN))
        server.enqueue(json(STRANGER_THINGS))
        client().airs(CalendarIds(tvdb = 305288))
        wallMs += Duration.ofDays(26).toMillis()
        server.enqueue(json(LOGIN.replace("token-1", "token-2")))
        server.enqueue(json(STRANGER_THINGS))
        client().airs(CalendarIds(tvdb = 305288))
        repeat(2) { server.takeRequest() }
        assertEquals("/v4/login", server.takeRequest().path)
        assertEquals("Bearer token-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun aRejectedTokenLogsInAgainOnce() = runBlocking {
        server.enqueue(json(LOGIN))
        server.enqueue(json("{}", 401))
        server.enqueue(json(LOGIN.replace("token-1", "token-2")))
        server.enqueue(json(STRANGER_THINGS))
        assertEquals(LocalTime.of(20, 0), client().airs(CalendarIds(tvdb = 305288))?.time)
        assertEquals(4, server.requestCount)
    }

    @Test fun aFailureKeepsTheOldTimeAndPausesTheTvdb() = runBlocking {
        server.enqueue(json(LOGIN))
        server.enqueue(json(STRANGER_THINGS))
        val tvdb = client()
        tvdb.airs(CalendarIds(tvdb = 305288))
        wallMs += Duration.ofHours(7).toMillis()
        server.enqueue(json("{}", 503))
        assertEquals(LocalTime.of(20, 0), tvdb.airs(CalendarIds(tvdb = 305288))?.time)
        assertEquals(LocalTime.of(20, 0), tvdb.airs(CalendarIds(tvdb = 305288))?.time)
        assertEquals(3, server.requestCount)
        assertThrows(CalendarFetchException::class.java) { runBlocking { tvdb.airs(CalendarIds(tvdb = 1)) } }
        assertEquals(3, server.requestCount)
    }

    @Test fun aFailureWithNothingKeptThrows() {
        server.enqueue(json(LOGIN))
        server.enqueue(json("{}", 500))
        assertThrows(IOException::class.java) { runBlocking { client().airs(CalendarIds(tvdb = 305288)) } }
    }

    @Test fun aShowWithoutAnAirsTimeHasNoTime() = runBlocking {
        server.enqueue(json(LOGIN))
        server.enqueue(json(STRANGER_THINGS.replace("\"20:00\"", "\"\"")))
        assertNull(client().airs(CalendarIds(tvdb = 305288)))
    }

    @Test fun readsAirsTimesInTheirUsualShapes() {
        assertEquals(LocalTime.of(20, 0), parseTvdbAirsTime("20:00"))
        assertEquals(LocalTime.of(21, 30), parseTvdbAirsTime("9:30 PM"))
        assertEquals(LocalTime.of(0, 15), parseTvdbAirsTime("12:15 am"))
        assertEquals(LocalTime.of(3, 0), parseTvdbAirsTime("03:00:00"))
        assertNull(parseTvdbAirsTime(""))
        assertNull(parseTvdbAirsTime("25:00"))
        assertNull(parseTvdbAirsTime("soon"))
    }

    @Test fun countriesMapToTheirBroadcastZone() {
        assertEquals(ZoneId.of("America/New_York"), tvdbCountryZone("usa"))
        assertEquals(ZoneId.of("Europe/London"), tvdbCountryZone("GBR"))
        assertEquals(ZoneId.of("Australia/Sydney"), tvdbCountryZone("aus"))
        assertNull(tvdbCountryZone("xyz"))
        assertNull(tvdbCountryZone(null))
    }

    @Test fun theNetworkCountryIsUsedWhenTheShowHasNone() {
        val show = tvdbCachedShow(
            com.nuvio.tv.data.remote.dto.TvdbSeriesDto(
                airsTime = "21:00",
                originalNetwork = com.nuvio.tv.data.remote.dto.TvdbCompanyDto(name = "BBC One", country = "gbr")
            ),
            0L
        )
        assertEquals("Europe/London", show.zone)
    }

    @Test fun theCacheStaysBounded() {
        val old = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val cache = TvdbCache(
            ids = mapOf("imdb:tt1" to TvdbCachedId(1, old), "imdb:tt2" to TvdbCachedId(2, wallMs)),
            shows = mapOf("1" to TvdbCachedShow(fetchedAtMs = old), "2" to TvdbCachedShow(fetchedAtMs = wallMs))
        )
        val pruned = prunedTvdbCache(cache, wallMs)
        assertEquals(setOf("imdb:tt2"), pruned.ids.keys)
        assertEquals(setOf("2"), pruned.shows.keys)
    }

    private val wednesdays = TvdbAirs(LocalTime.of(20, 0), ZoneId.of("America/New_York"), setOf(DayOfWeek.WEDNESDAY))
    private val now = Instant.parse("2026-10-24T10:00:00Z")

    @Test fun easternTimesCrossTheDaylightSavingChange() {
        val entries = listOf(
            CalendarEntry(season = 5, episode = 1, airDate = "2026-10-28"),
            CalendarEntry(season = 5, episode = 2, airDate = "2026-11-04")
        )
        val timed = calendarTvdbTimes(entries, wednesdays, now)
        assertEquals(Instant.parse("2026-10-29T00:00:00Z").toEpochMilli(), timed[0].airInstantMs)
        assertEquals(Instant.parse("2026-11-05T01:00:00Z").toEpochMilli(), timed[1].airInstantMs)
        assertTrue(timed.all { it.source == CalendarSource.TVDB && it.airDate == null })
        val sydney = ZoneId.of("Australia/Sydney")
        assertEquals(LocalDate.parse("2026-10-29"), timed[0].localDate(sydney))
        assertEquals("11:00", java.time.Instant.ofEpochMilli(timed[0].airInstantMs!!).atZone(sydney).toLocalTime().toString())
        assertEquals("12:00", java.time.Instant.ofEpochMilli(timed[1].airInstantMs!!).atZone(sydney).toLocalTime().toString())
    }

    @Test fun aDateOffTheAirDaysGetsNoTimeUnlessItIsTheNextEpisode() {
        val thursday = CalendarEntry(season = 1, episode = 3, airDate = "2026-10-29")
        assertTrue(calendarTvdbTimes(listOf(thursday), wednesdays, now).isEmpty())
        val withNext = wednesdays.copy(days = emptySet(), nextAired = LocalDate.parse("2026-10-29"))
        assertEquals(1, calendarTvdbTimes(listOf(thursday), withNext, now).size)
        // TheTVDB has it a day earlier: its time on this date would be 24 h late.
        val dayOff = withNext.copy(nextAired = LocalDate.parse("2026-10-28"))
        assertTrue(calendarTvdbTimes(listOf(thursday), dayOff, now).isEmpty())
        val farNext = withNext.copy(nextAired = LocalDate.parse("2026-10-26"))
        assertTrue(calendarTvdbTimes(listOf(thursday), farNext, now).isEmpty())
    }

    @Test fun timedPastAndFilmEntriesAreLeftAlone() {
        val entries = listOf(
            CalendarEntry(season = 1, episode = 1, airDate = "2026-10-14"),
            CalendarEntry(season = 1, episode = 2, airInstantMs = now.plus(Duration.ofDays(4)).toEpochMilli()),
            CalendarEntry(kind = CalendarEntryKind.MOVIE_DIGITAL, airDate = "2026-10-28")
        )
        assertTrue(calendarTvdbTimes(entries, wednesdays, now).isEmpty())
    }

    private companion object {
        const val LOGIN = """{"status":"success","data":{"token":"token-1"}}"""
        const val REMOTE_ID_SERIES = """{"status":"success","data":[{"series":{"id":305288,"name":"Stranger Things","slug":"stranger-things"}}]}"""
        const val STRANGER_THINGS = """{"status":"success","data":{"id":305288,"name":"Stranger Things",
            "airsTime":"20:00","airsDays":{"sunday":false,"monday":false,"tuesday":false,"wednesday":true,
            "thursday":false,"friday":false,"saturday":false},"country":null,"originalCountry":"usa","nextAired":"",
            "lastAired":"2026-01-01","originalNetwork":{"id":535,"name":"Netflix","country":"usa"},
            "latestNetwork":{"id":535,"name":"Netflix","country":"usa"},"episodes":[]}}"""
    }
}
