package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.core.calendar.CalendarClock
import com.nuvio.tv.core.calendar.CalendarEpisodeResolver
import com.nuvio.tv.core.calendar.CalendarReminder
import com.nuvio.tv.core.calendar.CalendarWatchTarget
import com.nuvio.tv.core.calendar.CalendarIds
import com.nuvio.tv.core.calendar.CalendarRefreshReason
import com.nuvio.tv.core.calendar.CalendarRefresher
import com.nuvio.tv.core.calendar.CalendarReminderCenter
import com.nuvio.tv.core.calendar.CalendarSettings
import com.nuvio.tv.core.calendar.CalendarSettingsDataStore
import com.nuvio.tv.core.calendar.CalendarState
import com.nuvio.tv.core.calendar.CalendarZoneProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.core.tracking.effectiveLibrarySourceMode
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.domain.repository.MetaRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import com.nuvio.tv.core.network.NetworkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Art, synopsis and runtime for the focused row. */
data class CalendarRowDetails(
    val backdrop: String? = null,
    val synopsis: String? = null,
    val runtimeMinutes: Int? = null,
    /** A film's runtime as its addon gave it. */
    val filmRuntime: String? = null,
    val logo: String? = null,
    val rating: Double? = null,
    val genre: String? = null,
    /** Episode pictures by "season:episode". */
    val stills: Map<String, String> = emptyMap()
) {
    fun still(season: Int?, episode: Int?): String? = if (season == null || episode == null) null else stills["$season:$episode"]

    val complete: Boolean get() = backdrop != null && synopsis != null
}

/** What the Calendar screen reads and changes; the refresher and the settings behind one seam. */
interface CalendarScreenData {
    val state: StateFlow<CalendarState>
    val settings: StateFlow<CalendarSettings?>
    val zones: Flow<ZoneId>
    fun now(): Instant
    fun requestRefresh(reason: CalendarRefreshReason)
    suspend fun setBell(titleKey: String, on: Boolean)
    suspend fun setHidden(titleKey: String, hidden: Boolean, name: String?)
    suspend fun setWindowDays(days: Int)
    suspend fun setMenuVisible(visible: Boolean)
    suspend fun setZoneOverride(zone: String?)
    suspend fun setCornerMessage(enabled: Boolean)
    suspend fun setIncludePersonalLists(include: Boolean)
    suspend fun setFollowOtherServices(follow: Boolean)
    /** The service the library comes from now, or null for the Nuvio library. */
    suspend fun librarySource(): TrackingProviderId? = null
    suspend fun setComingUpRow(enabled: Boolean)
    suspend fun setHiddenFromComingUp(titleKey: String, hidden: Boolean)
    suspend fun unhideAllFromComingUp()
    suspend fun setViewMode(mode: CalendarViewMode) {}
    /** From what the app already holds; with [fetch] the title's details are asked for once when they are missing. */
    suspend fun details(row: CalendarRow, fetch: Boolean = false): CalendarRowDetails?

    /** Where Watch now goes for an aired row; null when the episode cannot be found. */
    suspend fun watchTarget(row: CalendarRow): CalendarWatchTarget? = null

    /** Set when the reminder card asks the Calendar to open at Just aired. */
    val openAtJustAired: StateFlow<Boolean>
    fun consumeOpenAtJustAired()
}

class DefaultCalendarScreenData @Inject constructor(
    private val refresher: CalendarRefresher,
    private val settingsStore: CalendarSettingsDataStore,
    zoneProvider: CalendarZoneProvider,
    private val reminders: CalendarReminderCenter,
    private val metaRepository: MetaRepository,
    private val enrichmentCache: ContinueWatchingEnrichmentCache,
    private val episodeResolver: CalendarEpisodeResolver,
    @CalendarClock private val clock: Clock,
    private val traktSettings: TraktSettingsDataStore,
    private val trackingProviders: TrackingProviderRegistry
) : CalendarScreenData {
    override val state get() = refresher.state
    override val settings get() = settingsStore.settings
    override val zones = zoneProvider.zones

    private val snapshotLock = Mutex()
    private var snapshots: Pair<List<CachedNextUpItem>, List<CachedInProgressItem>>? = null

    override fun now(): Instant = clock.instant()
    override fun requestRefresh(reason: CalendarRefreshReason) = refresher.requestRefresh(reason)
    override suspend fun setViewMode(mode: CalendarViewMode) {
        settingsStore.setViewMode(mode.name)
    }

    override suspend fun setBell(titleKey: String, on: Boolean) {
        settingsStore.setBell(titleKey, on)
    }
    override suspend fun setHidden(titleKey: String, hidden: Boolean, name: String?) {
        settingsStore.setTitleHidden(titleKey, hidden, name)
        if (!hidden) refresher.requestRefresh(CalendarRefreshReason.SETTINGS_CHANGED)
    }
    override suspend fun setWindowDays(days: Int) {
        settingsStore.setWindowDays(days)
    }
    override suspend fun setMenuVisible(visible: Boolean) {
        settingsStore.setMenuVisible(visible)
    }
    override suspend fun setZoneOverride(zone: String?) {
        settingsStore.setZoneOverride(zone)
    }
    override suspend fun setCornerMessage(enabled: Boolean) {
        settingsStore.setCornerMessage(enabled)
    }
    override suspend fun setComingUpRow(enabled: Boolean) {
        settingsStore.setComingUpRow(enabled)
    }
    override suspend fun setHiddenFromComingUp(titleKey: String, hidden: Boolean) {
        settingsStore.setHiddenFromComingUp(titleKey, hidden)
    }
    override suspend fun unhideAllFromComingUp() {
        settingsStore.unhideAllFromComingUp()
    }
    override val openAtJustAired: StateFlow<Boolean> get() = reminders.openAtJustAired
    override fun consumeOpenAtJustAired() = reminders.consumeCalendarRequest()
    override suspend fun setIncludePersonalLists(include: Boolean) {
        settingsStore.setIncludePersonalLists(include)
        refresher.requestRefresh(CalendarRefreshReason.SETTINGS_CHANGED)
    }
    override suspend fun librarySource(): TrackingProviderId? =
        effectiveLibrarySourceMode(traktSettings.librarySourceMode.first()) { trackingProviders.isAuthenticated(it) }.providerId

    override suspend fun setFollowOtherServices(follow: Boolean) {
        settingsStore.setFollowOtherServices(follow)
        refresher.requestRefresh(CalendarRefreshReason.SETTINGS_CHANGED)
    }

    override suspend fun details(row: CalendarRow, fetch: Boolean): CalendarRowDetails? {
        val ids = rowContentIds(row)
        val meta = ids.firstNotNullOfOrNull { metaRepository.getCachedMeta(row.apiType, it) }
            ?: if (fetch) fetchMeta(row.apiType, ids) else null
        val video = meta?.videos?.firstOrNull { it.season == row.season && it.episode == row.episode && row.season != null }
        val (nextUp, inProgress) = cachedSnapshots()
        val aliases = ids.flatMapTo(hashSetOf()) { CalendarIds.fromContentId(it).aliases() }
        fun matches(contentId: String) = CalendarIds.fromContentId(contentId).aliases().any { it in aliases }
        val next = nextUp.firstOrNull { matches(it.contentId) }
        val started = inProgress.firstOrNull { matches(it.contentId) }
        val episodeText = video?.overview
            ?: next?.takeIf { it.season == row.season && it.episode == row.episode }?.episodeDescription
            ?: started?.takeIf { it.season == row.season && it.episode == row.episode }?.episodeDescription
        val details = CalendarRowDetails(
            backdrop = meta?.background ?: next?.backdrop ?: started?.backdrop,
            synopsis = (episodeText ?: meta?.description)?.trim()?.takeIf(String::isNotEmpty),
            runtimeMinutes = video?.runtime?.takeIf { it > 0 },
            filmRuntime = meta?.runtime?.takeIf { row.isFilm && it.isNotBlank() },
            logo = meta?.logo?.takeIf(String::isNotBlank) ?: next?.logo ?: started?.logo,
            rating = meta?.imdbRating?.toDouble()?.takeIf { it > 0 },
            genre = meta?.genres?.firstOrNull { it.isNotBlank() },
            stills = meta?.videos.orEmpty().mapNotNull { v ->
                val still = v.thumbnail?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (v.season == null || v.episode == null) null else "${v.season}:${v.episode}" to still
            }.toMap()
        )
        return details.takeIf { it.backdrop != null || it.synopsis != null || it.runtimeMinutes != null || it.filmRuntime != null }
    }

    override suspend fun watchTarget(row: CalendarRow): CalendarWatchTarget? {
        val title = refresher.state.value.titles.firstOrNull { it.matchesKey(row.titleKey) } ?: return null
        val slot = row.key.removePrefix("${row.titleKey}|").substringBefore('#')
        val entry = title.entries.firstOrNull { it.slot == slot } ?: return null
        return episodeResolver.resolve(CalendarReminder(title, entry, clock.instant()))
    }

    private suspend fun fetchMeta(type: String, ids: List<String>): com.nuvio.tv.domain.model.Meta? =
        withTimeoutOrNull(DETAILS_FETCH_MS) {
            ids.firstNotNullOfOrNull { id ->
                val result = try {
                    metaRepository.getMetaFromAllAddons(type, id).first { it !is NetworkResult.Loading }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                (result as? NetworkResult.Success)?.data
            }
        }

    private suspend fun cachedSnapshots() = snapshotLock.withLock {
        snapshots ?: runCatching {
            enrichmentCache.getNextUpSnapshot() to enrichmentCache.getInProgressSnapshot()
        }.getOrDefault(emptyList<CachedNextUpItem>() to emptyList()).also { snapshots = it }
    }
}

private const val DETAILS_FETCH_MS = 6_000L

internal fun rowContentIds(row: CalendarRow): List<String> {
    val ids = parseIds(row.titleKey)
    return listOfNotNull(row.contentId, ids?.imdb, ids?.tmdb?.let { "tmdb:$it" }).distinct()
}

private fun parseIds(titleKey: String): CalendarIds? =
    com.nuvio.tv.core.calendar.parseCalendarTitleKey(titleKey)?.second
