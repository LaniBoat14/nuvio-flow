package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.Meta
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherChannelItemsTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun settings(
        daysCap: Int = TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL,
        dismissed: Set<String> = emptySet(),
        episodeThumbnails: Boolean = false
    ) = ChannelSettingsSnapshot(daysCap, dismissed, episodeThumbnails)

    private fun inProgress(
        contentId: String,
        lastWatched: Long,
        season: Int? = null,
        episode: Int? = null,
        backdrop: String? = "https://img/backdrop.jpg",
        episodeThumbnail: String? = null
    ) = CachedInProgressItem(
        contentId = contentId,
        contentType = if (season != null) "series" else "movie",
        name = "Title $contentId",
        poster = "https://img/poster.jpg",
        backdrop = backdrop,
        logo = null,
        videoId = if (season != null) "$contentId:$season:$episode" else contentId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = 600_000L,
        duration = 5_400_000L,
        lastWatched = lastWatched,
        progressPercent = 11f,
        episodeThumbnail = episodeThumbnail
    )

    private fun nextUp(
        contentId: String,
        sortTimestamp: Long,
        hasAired: Boolean = true,
        seedSeason: Int? = 1,
        seedEpisode: Int? = 1,
        backdrop: String? = "https://img/backdrop.jpg",
        thumbnail: String? = null
    ) = CachedNextUpItem(
        contentId = contentId,
        contentType = "series",
        name = "Show $contentId",
        poster = "https://img/poster.jpg",
        backdrop = backdrop,
        logo = null,
        videoId = "$contentId:1:2",
        season = 1,
        episode = 2,
        episodeTitle = "Next",
        thumbnail = thumbnail,
        hasAired = hasAired,
        lastWatched = sortTimestamp,
        sortTimestamp = sortTimestamp,
        seedSeason = seedSeason,
        seedEpisode = seedEpisode
    )

    @Test
    fun `in-progress wins over next-up for the same title and the list keeps one row per title`() {
        val items = buildChannelItems(
            inProgress = listOf(inProgress("tt1", now - day, season = 1, episode = 3)),
            nextUp = listOf(nextUp("tt1", now)),
            settings = settings(),
            nowMs = now
        )
        assertEquals(1, items.size)
        assertEquals(3, items.single().episode)
        assertEquals(600_000L, items.single().position)
    }

    @Test
    fun `newest first across in-progress and next-up`() {
        val items = buildChannelItems(
            inProgress = listOf(inProgress("old", now - 3 * day), inProgress("new", now - day)),
            nextUp = listOf(nextUp("mid", now - 2 * day)),
            settings = settings(),
            nowMs = now
        )
        assertEquals(listOf("new", "mid", "old"), items.map { it.contentId })
    }

    @Test
    fun `days cap drops titles watched before the window`() {
        val items = buildChannelItems(
            inProgress = listOf(inProgress("fresh", now - 2 * day), inProgress("stale", now - 10 * day)),
            nextUp = listOf(nextUp("staleNext", now - 10 * day)),
            settings = settings(daysCap = 7),
            nowMs = now
        )
        assertEquals(listOf("fresh"), items.map { it.contentId })
    }

    @Test
    fun `days cap of all keeps everything`() {
        val items = buildChannelItems(
            inProgress = listOf(inProgress("ancient", now - 400 * day)),
            nextUp = emptyList(),
            settings = settings(daysCap = TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL),
            nowMs = now
        )
        assertEquals(1, items.size)
    }

    @Test
    fun `unaired and dismissed next-up episodes stay out of the channel`() {
        val items = buildChannelItems(
            inProgress = emptyList(),
            nextUp = listOf(
                nextUp("unaired", now, hasAired = false),
                nextUp("dismissed", now, seedSeason = 2, seedEpisode = 5),
                nextUp("shown", now)
            ),
            settings = settings(dismissed = setOf("dismissed_s2e5")),
            nowMs = now
        )
        assertEquals(listOf("shown"), items.map { it.contentId })
    }

    @Test
    fun `dismiss key follows the seed episode`() {
        assertEquals("tt9_s2e5", nextUpDismissKey(nextUp("tt9", now, seedSeason = 2, seedEpisode = 5)))
        assertEquals("tt9_s2", nextUpDismissKey(nextUp("tt9", now, seedSeason = 2, seedEpisode = null)))
        assertEquals("tt9", nextUpDismissKey(nextUp("tt9", now, seedSeason = null, seedEpisode = null)))
    }

    @Test
    fun `next-up rows carry no position so the launcher draws no progress bar`() {
        val item = buildChannelItems(emptyList(), listOf(nextUp("tt2", now)), settings(), now).single()
        assertEquals(0L, item.position)
        assertEquals(0L, item.duration)
        assertNull(item.progressPercent)
        assertEquals(2, item.episode)
    }

    @Test
    fun `episode thumbnails replace the backdrop only when the setting is on`() {
        val ip = inProgress("tt3", now, season = 1, episode = 1, episodeThumbnail = "https://img/ep.jpg")
        val nu = nextUp("tt4", now, thumbnail = "https://img/next.jpg")

        val plain = buildChannelItems(listOf(ip), listOf(nu), settings(episodeThumbnails = false), now)
        assertEquals("https://img/backdrop.jpg", plain.first { it.contentId == "tt3" }.backdrop)
        assertEquals("https://img/backdrop.jpg", plain.first { it.contentId == "tt4" }.backdrop)

        val thumbs = buildChannelItems(listOf(ip), listOf(nu), settings(episodeThumbnails = true), now)
        assertEquals("https://img/ep.jpg", thumbs.first { it.contentId == "tt3" }.backdrop)
        assertEquals("https://img/next.jpg", thumbs.first { it.contentId == "tt4" }.backdrop)

        val noThumb = buildChannelItems(
            listOf(inProgress("tt5", now, episodeThumbnail = null)), emptyList(), settings(episodeThumbnails = true), now
        )
        assertEquals("https://img/backdrop.jpg", noThumb.single().backdrop)
    }

    @Test
    fun `channel name puts the app first`() {
        assertEquals("Nuvio Arc: Continue Watching", launcherChannelName("Nuvio Arc", "Continue Watching"))
        assertEquals("Nuvio Arc: Weiterschauen", launcherChannelName(" Nuvio Arc ", "Weiterschauen "))
    }

    @Test
    fun `channels need a leanback home that is not Fire OS`() {
        assertTrue(launcherChannelsSupported(hasLeanback = true, isFireTv = false))
        assertFalse(launcherChannelsSupported(hasLeanback = true, isFireTv = true))
        assertFalse(launcherChannelsSupported(hasLeanback = false, isFireTv = false))
        assertFalse(launcherChannelsSupported(hasLeanback = false, isFireTv = true))
    }

    @Test
    fun `channel synopsis uses the cached episode text before any meta`() {
        val episode = inProgress("tt1", now, season = 1, episode = 3).copy(episodeDescription = "Episode text")
        val movie = inProgress("tt2", now - day)
        val upNext = nextUp("tt3", now - 2 * day)
        val items = buildChannelItems(listOf(episode, movie), listOf(upNext), settings(), now)
        val lookups = mutableListOf<String>()
        val movieMeta = mockk<Meta> {
            every { description } returns "Movie text"
            every { videos } returns emptyList()
        }

        val text = channelDescriptions(items, listOf(episode, movie), listOf(upNext)) { _, id ->
            lookups += id
            if (id == "tt2") movieMeta else null
        }

        assertEquals(mapOf("tt1:1:3" to "Episode text", "tt2" to "Movie text"), text)
        assertEquals(listOf("tt2", "tt3"), lookups)
    }
}
