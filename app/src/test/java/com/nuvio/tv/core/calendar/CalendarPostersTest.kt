package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPostersTest {
    private fun candidate(imdb: String, poster: String? = null, type: CalendarTitleType = CalendarTitleType.SERIES) =
        CalendarTitleCandidate(CalendarIds(imdb = imdb), type, CalendarOrigin.TRAKT_WATCHED, contentId = imdb, poster = poster)

    @Test fun theAddonPosterInMemoryComesBeforeContinueWatching() {
        val posters = calendarCachedPosters(
            listOf(candidate("tt1")),
            metaPoster = { type, id -> if (type == "series" && id == "tt1") "addon.jpg" else null },
            nextUpPosters = listOf("tt1" to "next-up.jpg")
        )
        assertEquals("addon.jpg", posters["imdb:tt1"])
    }

    @Test fun continueWatchingFillsInWhenTheDetailsAreNotInMemory() {
        val posters = calendarCachedPosters(
            listOf(candidate("tt1")),
            metaPoster = { _, _ -> null },
            nextUpPosters = listOf("tt1" to "next-up.jpg", "tt9" to "other.jpg")
        )
        assertEquals(mapOf("imdb:tt1" to "next-up.jpg"), posters)
    }

    @Test fun titlesWithAPosterAskForNothing() {
        var asked = 0
        val posters = calendarCachedPosters(
            listOf(candidate("tt1", poster = "own.jpg")),
            metaPoster = { _, _ -> asked++; "addon.jpg" },
            nextUpPosters = listOf("tt1" to "next-up.jpg")
        )
        assertTrue(posters.isEmpty())
        assertEquals(0, asked)
    }

    @Test fun filmsAreLookedUpAsFilms() {
        val posters = calendarCachedPosters(
            listOf(candidate("tt2", type = CalendarTitleType.MOVIE)),
            metaPoster = { type, _ -> if (type == "movie") "film.jpg" else null },
            nextUpPosters = emptyList()
        )
        assertEquals("film.jpg", posters["imdb:tt2"])
    }

    @Test fun onlyTitlesWithoutAPosterAreFilled() {
        val bare = CalendarFollowedTitle("series:imdb:tt1", CalendarIds(imdb = "tt1"), CalendarTitleType.SERIES, emptySet(), null)
        val own = bare.copy(key = "series:imdb:tt2", ids = CalendarIds(imdb = "tt2"), poster = "own.jpg")
        val other = bare.copy(key = "series:imdb:tt3", ids = CalendarIds(imdb = "tt3"))
        val filled = listOf(bare, own, other).withCachedPosters(mapOf("imdb:tt1" to "cached.jpg", "imdb:tt2" to "cached2.jpg"))
        assertEquals("cached.jpg", filled[0].poster)
        assertEquals("own.jpg", filled[1].poster)
        assertNull(filled[2].poster)
    }

    @Test fun aCachedPosterReplacesAStoredFallbackAndAStoredOneStaysWithoutIt() {
        val title = CalendarFollowedTitle("series:imdb:tt1", CalendarIds(imdb = "tt1"), CalendarTitleType.SERIES, emptySet(), null)
        val stored = CalendarTitleRecord("series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"), poster = "tvmaze.jpg")
        val withCached = reconcileCalendarRecords(listOf(title).withCachedPosters(mapOf("imdb:tt1" to "addon.jpg")), listOf(stored))
        assertEquals("addon.jpg", withCached.single().poster)
        val without = reconcileCalendarRecords(listOf(title), listOf(stored))
        assertEquals("tvmaze.jpg", without.single().poster)
    }

    @Test fun theBackdropInMemoryComesBeforeContinueWatching() {
        val art = calendarCachedArt(
            listOf(candidate("tt1"), candidate("tt2")),
            metaArt = { type, id -> if (type == "series" && id == "tt1") CalendarArt("addon.jpg", "logo.png") else null },
            continueWatchingArt = listOf("tt1" to CalendarArt("next-up.jpg"), "tt2" to CalendarArt("in-progress.jpg"))
        )
        assertEquals(CalendarArt("addon.jpg", "logo.png"), art["imdb:tt1"])
        assertEquals(CalendarArt("in-progress.jpg"), art["imdb:tt2"])
    }

    @Test fun artWithoutABackdropCountsForNothing() {
        val art = calendarCachedArt(
            listOf(candidate("tt1")),
            metaArt = { _, _ -> CalendarArt(null, "logo.png") },
            continueWatchingArt = listOf("tt1" to CalendarArt(" "), "tt9" to CalendarArt("other.jpg"))
        )
        assertTrue(art.isEmpty())
    }

    @Test fun aSavedBackdropStaysUntilTheAppHoldsAnother() {
        val saved = CalendarTitleRecord(
            "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1", tmdb = 1), backdrop = "saved.jpg", logo = "saved.png"
        )
        val bare = saved.copy(key = "series:imdb:tt2", ids = CalendarIds(imdb = "tt2"), backdrop = null, logo = null)
        val kept = listOf(saved, bare).withCachedArt(mapOf("imdb:tt9" to CalendarArt("x.jpg")))
        assertEquals(listOf(saved, bare), kept)
        val updated = listOf(saved, bare).withCachedArt(
            mapOf("tmdb:1" to CalendarArt("new.jpg"), "imdb:tt2" to CalendarArt("cw.jpg", "cw.png"))
        )
        assertEquals("new.jpg", updated[0].backdrop)
        assertEquals("saved.png", updated[0].logo)
        assertEquals("cw.jpg", updated[1].backdrop)
        assertEquals("cw.png", updated[1].logo)
    }

    @Test fun reconcileKeepsTheSavedBackdrop() {
        val title = CalendarFollowedTitle("series:imdb:tt1", CalendarIds(imdb = "tt1"), CalendarTitleType.SERIES, emptySet(), null)
        val stored = CalendarTitleRecord(
            "series:imdb:tt1", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1"), backdrop = "saved.jpg", backdropCheckedAtMs = 7L
        )
        val record = reconcileCalendarRecords(listOf(title), listOf(stored)).single()
        assertEquals("saved.jpg", record.backdrop)
        assertEquals(7L, record.backdropCheckedAtMs)
    }

    private val now = Instant.parse("2026-10-05T23:00:00Z")

    private fun upcoming(n: Int, inDays: Long, backdrop: String? = null, checkedAtMs: Long = 0L) = CalendarTitleRecord(
        "series:imdb:tt$n", CalendarTitleType.SERIES, CalendarIds(imdb = "tt$n"), name = "Show $n",
        backdrop = backdrop, backdropCheckedAtMs = checkedAtMs,
        sources = mapOf(
            CalendarSource.TVMAZE to listOf(
                CalendarEntry(season = 1, episode = 1, airInstantMs = now.plus(Duration.ofDays(inDays)).toEpochMilli())
            )
        )
    )

    @Test fun titlesWhoseArtIsDueAreAskedForSoonestFirst() {
        val day = Duration.ofDays(1).toMillis()
        val titles = listOf(
            upcoming(1, 3),
            upcoming(2, 2, backdrop = "has.jpg", checkedAtMs = now.toEpochMilli() - 2 * day),
            upcoming(3, 1, checkedAtMs = now.minus(Duration.ofHours(2)).toEpochMilli()),
            upcoming(4, 4, checkedAtMs = now.toEpochMilli() - 2 * day),
            upcoming(5, 30, backdrop = "full.jpg", checkedAtMs = now.toEpochMilli() - 8 * day),
            upcoming(7, 1, backdrop = "cw.jpg"),
            CalendarTitleRecord("series:imdb:tt6", CalendarTitleType.SERIES, CalendarIds(imdb = "tt6"))
        )
        val wanted = calendarBackdropsWanted(titles, now, max = 4)
        assertEquals(listOf("series:imdb:tt7", "series:imdb:tt1", "series:imdb:tt4", "series:imdb:tt5"), wanted.map { it.key })
    }

    @Test fun aLookupIsNotedWhateverItFound() {
        val title = upcoming(1, 1).copy(logo = "old.png", rating = 7.0, genre = "Drama")
        val missed = title.withFetchedArt(null, now)
        assertNull(missed.backdrop)
        assertEquals(now.toEpochMilli(), missed.backdropCheckedAtMs)
        assertEquals("old.png", missed.logo)
        assertEquals(7.0, missed.rating!!, 0.0)
        val found = title.withFetchedArt(CalendarArt("fetched.jpg", null, 8.4, "Crime"), now)
        assertEquals("fetched.jpg", found.backdrop)
        assertEquals("old.png", found.logo)
        assertEquals(8.4, found.rating!!, 0.0)
        assertEquals("Crime", found.genre)
        assertEquals(now.toEpochMilli(), found.backdropCheckedAtMs)
        assertTrue(calendarBackdropsWanted(listOf(missed), now.plus(Duration.ofHours(23))).isEmpty())
        assertEquals(1, calendarBackdropsWanted(listOf(missed), now.plus(Duration.ofHours(24))).size)
        assertTrue(calendarBackdropsWanted(listOf(found), now.plus(Duration.ofDays(6))).isEmpty())
        assertEquals(1, calendarBackdropsWanted(listOf(found), now.plus(Duration.ofDays(7))).size)
    }
}
