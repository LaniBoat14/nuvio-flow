package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AndroidTvChannelSyncServiceTest {

    private val context: Context = mockk(relaxed = true)
    private val manager: AndroidTvChannelManager = mockk(relaxed = true)
    private val cache: ContinueWatchingEnrichmentCache = mockk(relaxed = true)
    private val layout: LayoutPreferenceDataStore = mockk(relaxed = true)
    private val trakt: TraktSettingsDataStore = mockk(relaxed = true)
    private val recommendations: TvRecommendationManager = mockk(relaxed = true)
    private val metaRepository: MetaRepository = mockk(relaxed = true)

    private lateinit var service: AndroidTvChannelSyncService

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { manager.isSupported() } returns true
        every { trakt.continueWatchingDaysCap } returns flowOf(TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL)
        every { trakt.dismissedNextUpKeys } returns flowOf(emptySet())
        every { layout.useEpisodeThumbnailsInCw } returns flowOf(false)
        every { layout.customPosterUrlPattern } returns flowOf("")
        every { layout.customPosterEnabledScreens } returns flowOf(emptySet())
        coEvery { cache.getInProgressSnapshot() } returns listOf(inProgress("tt1", 2_000L), inProgress("tt2", 1_000L))
        coEvery { cache.getNextUpSnapshot() } returns listOf(nextUp("tt3", 1_500L))
        TvRecommendationManager.isPlaybackActive.value = false
        service = AndroidTvChannelSyncService(context, manager, cache, layout, trakt, recommendations, metaRepository)
    }

    @After
    fun tearDown() {
        TvRecommendationManager.isPlaybackActive.value = false
        unmockkAll()
    }

    @Test
    fun `channel and Watch Next get the same list as the Home row`() = runTest {
        val channelItems = slot<List<WatchProgress>>()
        val watchNextItems = slot<List<WatchProgress>>()
        coEvery { manager.reconcile(capture(channelItems), any()) } returns Unit
        coEvery { recommendations.updateWatchNext(capture(watchNextItems), any()) } returns Unit

        service.reconcileFromCache()

        assertEquals(listOf("tt1", "tt3", "tt2"), channelItems.captured.map { it.contentId })
        assertEquals(channelItems.captured, watchNextItems.captured)
    }

    @Test
    fun `nothing is written while frames are on screen`() = runTest {
        TvRecommendationManager.isPlaybackActive.value = true

        service.reconcileFromCache()

        coVerify(exactly = 0) { manager.reconcile(any(), any()) }
        coVerify(exactly = 0) { recommendations.updateWatchNext(any(), any()) }
        coVerify(exactly = 0) { cache.getInProgressSnapshot() }
    }

    @Test
    fun `a box without channels reads and writes nothing`() = runTest {
        every { manager.isSupported() } returns false

        service.reconcileFromCache()

        coVerify(exactly = 0) { manager.reconcile(any(), any()) }
        coVerify(exactly = 0) { recommendations.updateWatchNext(any(), any()) }
        coVerify(exactly = 0) { cache.getInProgressSnapshot() }
    }

    @Test
    fun `the catch-up after a pause updates the channel and leaves Watch Next to the player`() = runTest {
        service.reconcileFromCache(withWatchNext = false)

        coVerify(exactly = 1) { manager.reconcile(any(), any()) }
        coVerify(exactly = 0) { recommendations.updateWatchNext(any(), any()) }
    }

    @Test
    fun `settings passed in are used as they are`() = runTest {
        val channelItems = slot<List<WatchProgress>>()
        coEvery { manager.reconcile(capture(channelItems), any()) } returns Unit

        service.reconcileFromCache(ChannelSettingsSnapshot(daysCap = 0, dismissedNextUp = setOf("tt3_s1e1"), useEpisodeThumbnails = false))

        assertEquals(listOf("tt1", "tt2"), channelItems.captured.map { it.contentId })
    }

    private fun inProgress(contentId: String, lastWatched: Long) = CachedInProgressItem(
        contentId = contentId,
        contentType = "movie",
        name = "Title $contentId",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1_000L,
        duration = 10_000L,
        lastWatched = lastWatched,
        progressPercent = 10f
    )

    private fun nextUp(contentId: String, sortTimestamp: Long) = CachedNextUpItem(
        contentId = contentId,
        contentType = "series",
        name = "Show $contentId",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:1:2",
        season = 1,
        episode = 2,
        episodeTitle = null,
        thumbnail = null,
        lastWatched = sortTimestamp,
        sortTimestamp = sortTimestamp,
        seedSeason = 1,
        seedEpisode = 1
    )

    @Test
    fun `cached episode synopsis reaches the channel and Watch Next`() = runTest {
        every { metaRepository.getCachedMeta(any(), any()) } returns null
        coEvery { cache.getNextUpSnapshot() } returns listOf(nextUp("tt3", 1_500L).copy(episodeDescription = "Episode synopsis"))
        val channelText = slot<Map<String, String>>()
        val watchNextText = slot<Map<String, String>>()
        coEvery { manager.reconcile(any(), capture(channelText)) } returns Unit
        coEvery { recommendations.updateWatchNext(any(), capture(watchNextText)) } returns Unit

        service.reconcileFromCache()

        assertEquals(mapOf("tt3:1:2" to "Episode synopsis"), channelText.captured)
        assertEquals(channelText.captured, watchNextText.captured)
    }
}
