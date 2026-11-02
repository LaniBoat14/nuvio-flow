package com.nuvio.tv.ui.screens.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.calendar.CalendarEntry
import com.nuvio.tv.core.calendar.CalendarIds
import com.nuvio.tv.core.calendar.CalendarSettingsDataStore
import com.nuvio.tv.core.calendar.CalendarSource
import com.nuvio.tv.core.calendar.CalendarTitleRecord
import com.nuvio.tv.core.calendar.CalendarTitleType
import com.nuvio.tv.core.calendar.CalendarWatchedIndex
import com.nuvio.tv.core.calendar.buildCalendarComingUp
import com.nuvio.tv.core.calendar.calendarContinueWatchingKeys
import com.nuvio.tv.core.calendar.calendarEntryInKeys
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.ui.screens.calendar.CalendarFormats
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ComingUpHideTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val now = Instant.parse("2026-10-17T23:00:00Z")
    private val formats = CalendarFormats.plain(Locale.UK, is24Hour = false)
    private val text = CalendarComingUpText("New", "Today", "Today %1\$s", "Tonight %1\$s", "Tomorrow", "In cinemas", "Digital")

    private fun show(id: String, vararg entries: CalendarEntry, ids: CalendarIds = CalendarIds(imdb = id)) =
        CalendarTitleRecord(
            key = "series:imdb:$id", type = CalendarTitleType.SERIES, ids = ids, name = "Show $id",
            contentId = id, sources = mapOf(CalendarSource.TVMAZE to entries.toList())
        )

    private fun on(date: String, episode: Int) = CalendarEntry(season = 1, episode = episode, airDate = date)

    private fun inProgress(contentId: String, episode: Int) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = contentId, contentType = "series", name = contentId, poster = null, backdrop = null, logo = null,
            videoId = "$contentId:1:$episode", season = 1, episode = episode, episodeTitle = null, thumbnail = null,
            hasAired = true, lastWatched = 1L, sortTimestamp = 1L
        )
    )

    private val anime = show("tt1", on("2026-10-16", 4), on("2026-10-22", 5))
    private val drama = show("tt2", on("2026-10-21", 2))

    private fun comingUp(titles: List<CalendarTitleRecord>, hidden: Set<String>, continueWatching: List<ContinueWatchingItem> = emptyList()) =
        calendarContinueWatchingKeys(continueWatching.map { (it as ContinueWatchingItem.NextUp).info.let { i -> Triple(i.contentId, i.season, i.episode) } })
            .let { shown ->
                buildCalendarComingUp(calendarTitlesShownInComingUp(titles, hidden), CalendarWatchedIndex(), now, sydney, { title, entry ->
                    calendarEntryInKeys(shown, title, entry)
                })
            }
            .mapNotNull { calendarComingUpCard(it, text, formats) }

    @Test fun aHiddenShowLeavesComingUpButStaysInTheCalendar() {
        val calendar = listOf(anime, drama)
        val cards = comingUp(calendar, setOf("series:imdb:tt1"))
        assertEquals(listOf("tt2"), cards.map { it.info.contentId }.distinct())
        assertEquals(listOf("tt1", "tt2"), calendar.map { it.contentId })
        assertEquals(2, anime.entries.size)
    }

    @Test fun aCardCarriesTheKeyItIsHiddenBy() {
        val card = comingUp(listOf(drama), emptySet()).single()
        assertEquals("series:imdb:tt2", card.info.comingUpKey)
    }

    @Test fun aKeyMadeFromAnotherIdStillHidesTheShow() {
        val both = show("tt3", on("2026-10-21", 1), ids = CalendarIds(imdb = "tt3", tmdb = 33)).copy(key = "series:tmdb:33")
        assertTrue(calendarTitlesShownInComingUp(listOf(both), setOf("series:imdb:tt3")).isEmpty())
    }

    @Test fun aShowWithAnIdTheCalendarCannotReadIsHiddenByItsKey() {
        val kitsu = show("x", on("2026-10-21", 1), ids = CalendarIds()).copy(key = "series:kitsu:7", contentId = "kitsu:7")
        assertTrue(calendarTitlesShownInComingUp(listOf(kitsu, drama), setOf("series:kitsu:7")) == listOf(drama))
    }

    @Test fun nothingHiddenKeepsTheSameList() {
        val titles = listOf(anime, drama)
        assertSame(titles, calendarTitlesShownInComingUp(titles, emptySet()))
    }

    @Test fun continueWatchingIsNotTouched() {
        val continueWatching = listOf(inProgress("tt1", 3))
        val state = HomeUiState(continueWatchingItems = continueWatching)
        comingUp(listOf(anime, drama), setOf("series:imdb:tt1"), continueWatching)
        assertEquals(continueWatching, state.continueWatchingItems)
    }

    private class MemoryStore : DataStore<Preferences> {
        private val mutex = Mutex()
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private fun store(active: MutableStateFlow<Int>): CalendarSettingsDataStore {
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns active
        val factory = mockk<ProfileDataStoreFactory>()
        val first = MemoryStore()
        val second = MemoryStore()
        every { factory.get(1, "calendar_settings") } returns first
        every { factory.get(2, "calendar_settings") } returns second
        return CalendarSettingsDataStore(factory, manager)
    }

    @Test fun hiddenShowsBelongToTheProfileThatHidThem() = runBlocking {
        val active = MutableStateFlow(1)
        val settings = store(active)
        settings.setHiddenFromComingUp("series:imdb:tt1", hidden = true, name = "Anime")
        val mine = settings.current(1)
        assertEquals(setOf("series:imdb:tt1"), mine.comingUpHiddenKeys)
        assertEquals(mapOf("series:imdb:tt1" to "Anime"), mine.comingUpHiddenNames)
        assertTrue(mine.hiddenTitleKeys.isEmpty())
        assertTrue(settings.current(2).comingUpHiddenKeys.isEmpty())
        active.value = 2
        settings.setHiddenFromComingUp("series:imdb:tt2", hidden = true, name = "Drama")
        assertEquals(setOf("series:imdb:tt1"), settings.current(1).comingUpHiddenKeys)
        assertEquals(setOf("series:imdb:tt2"), settings.current(2).comingUpHiddenKeys)
    }

    @Test fun unhidingBringsTheShowBack() = runBlocking {
        val settings = store(MutableStateFlow(1))
        settings.setHiddenFromComingUp("series:imdb:tt1", hidden = true, name = "Anime")
        settings.setHiddenFromComingUp("series:imdb:tt2", hidden = true, name = "Drama")
        settings.setHiddenFromComingUp("series:imdb:tt1", hidden = false)
        val left = settings.current(1)
        assertEquals(setOf("series:imdb:tt2"), left.comingUpHiddenKeys)
        assertEquals(setOf("series:imdb:tt2"), left.comingUpHiddenNames.keys)
        assertEquals(setOf("tt1"), comingUp(listOf(anime, drama), left.comingUpHiddenKeys).map { it.info.contentId }.toSet())
        settings.unhideAllFromComingUp()
        val none = settings.current(1)
        assertTrue(none.comingUpHiddenKeys.isEmpty() && none.comingUpHiddenNames.isEmpty())
        assertEquals(setOf("tt1", "tt2"), comingUp(listOf(anime, drama), none.comingUpHiddenKeys).map { it.info.contentId }.toSet())
    }
}
