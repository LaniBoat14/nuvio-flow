package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.data.repository.MetaCaller
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.Lazy
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Set by a screen that asks for addon artwork itself, only for the cards it is about to show. */
object ArtworkOnDemand : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<ArtworkOnDemand>
}

/**
 * Addon artwork for server titles that carry a catalogue id. Only the small fields a row or the
 * hero shows are kept, so a server page does not pin full metas in memory.
 */
@Singleton
class ServerArtwork internal constructor(
    private val metaRepository: Lazy<MetaRepository>,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(metaRepository: Lazy<MetaRepository>) :
        this(metaRepository, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private class Artwork(
        val name: String?,
        val poster: String?,
        val background: String?,
        val logo: String?,
        val landscapePoster: String?,
        val description: String?,
        val imdbRating: Float?,
        val genres: List<String>
    )

    private class Entry(val artwork: Artwork?, val expiresAtMs: Long)

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > MAX_ENTRIES
    }
    private val inFlight = ConcurrentHashMap<String, Job>()
    private val permits = Semaphore(MAX_CONCURRENT_FETCHES)
    private val queueLock = Any()
    private val queued = LinkedHashMap<String, MetaPreview>()
    private var queueRunning = 0
    private val revision = MutableStateFlow(0L)

    /** Moves on each time a lookup brings new artwork. */
    val updates: StateFlow<Long> = revision.asStateFlow()

    fun cached(items: List<MetaPreview>): List<MetaPreview> {
        var changed = false
        val mapped = items.map { item ->
            val artwork = key(item)?.let { shown(it, item) } ?: return@map item
            item.with(artwork).also { if (it != item) changed = true }
        }
        return if (changed) mapped else items
    }

    suspend fun resolve(items: List<MetaPreview>, waitMs: Long): List<MetaPreview> {
        val pending = items.mapNotNull { item ->
            val key = key(item) ?: return@mapNotNull null
            if (entry(key, item) != null) null else fetch(key, item)
        }
        if (pending.isNotEmpty()) withTimeoutOrNull(waitMs) { pending.joinAll() }
        return cached(items)
    }

    /**
     * Looks up the given titles in the background, newest call first, so the cards on screen
     * win over ones the user has already scrolled past.
     */
    fun request(items: List<MetaPreview>) {
        val wanted = LinkedHashMap<String, MetaPreview>()
        items.forEach { item ->
            val key = key(item) ?: return@forEach
            if (key in wanted || inFlight.containsKey(key) || entry(key, item) != null) return@forEach
            wanted[key] = item
        }
        if (wanted.isEmpty()) return
        synchronized(queueLock) {
            val older = queued.entries.filter { it.key !in wanted }
            queued.clear()
            queued.putAll(wanted)
            for ((key, item) in older) {
                if (queued.size >= MAX_QUEUED) break
                queued[key] = item
            }
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val next = synchronized(queueLock) {
                if (queueRunning >= MAX_CONCURRENT_FETCHES || queued.isEmpty()) return
                val first = queued.entries.first()
                queued.remove(first.key)
                queueRunning++
                first
            }
            if (entry(next.key, next.value) != null) {
                synchronized(queueLock) { queueRunning-- }
                continue
            }
            fetch(next.key, next.value).invokeOnCompletion {
                synchronized(queueLock) { queueRunning-- }
                drain()
            }
        }
    }

    suspend fun settle(items: List<MetaPreview>, timeoutMs: Long) {
        val pending = items.mapNotNull { item -> key(item)?.let(inFlight::get) }
        if (pending.isNotEmpty()) withTimeoutOrNull(timeoutMs) { pending.joinAll() }
    }

    private fun key(item: MetaPreview): String? {
        if (ServerItemRef.isServerId(item.id) || ServerMediaKind.fromContentType(item.apiType) == null) return null
        return "${item.apiType}:${item.id}"
    }

    private fun entry(key: String, item: MetaPreview): Entry? {
        synchronized(cache) {
            val entry = cache[key]
            if (entry != null && entry.expiresAtMs > nowMs()) return entry
        }
        val meta = metaRepository.get().getCachedMeta(item.apiType, item.id) ?: return null
        return store(key, meta.artwork())
    }

    // An expired hit still beats the server poster: a card that showed addon art keeps it.
    private fun shown(key: String, item: MetaPreview): Artwork? =
        entry(key, item)?.artwork ?: synchronized(cache) { cache[key]?.artwork }

    private class Loaded(val artwork: Artwork?, val final: Boolean)

    private fun store(key: String, artwork: Artwork?, final: Boolean = true): Entry {
        val ttl = when {
            artwork != null -> HIT_TTL_MS
            final -> MISS_TTL_MS
            else -> RETRY_TTL_MS
        }
        synchronized(cache) {
            val kept = artwork ?: cache[key]?.artwork
            val entry = Entry(kept, nowMs() + ttl)
            cache[key] = entry
            return entry
        }
    }

    private fun fetch(key: String, item: MetaPreview): Job {
        inFlight[key]?.let { return it }
        val type = item.apiType
        val id = item.id
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val loaded = permits.withPermit { load(type, id) }
            store(key, loaded.artwork, loaded.final)
            if (loaded.artwork != null) revision.update { it + 1 }
        }
        inFlight.putIfAbsent(key, job)?.let { existing ->
            job.cancel()
            return existing
        }
        job.invokeOnCompletion { inFlight.remove(key, job) }
        job.start()
        return job
    }

    private suspend fun load(type: String, id: String): Loaded =
        try {
            val repository = metaRepository.get()
            val cached = repository.getCachedMeta(type, id)
            if (cached != null) {
                Loaded(cached.artwork(), final = true)
            } else {
                val result = withContext(MetaCaller(CALLER)) {
                    repository.getMetaFromAllAddons(type, id).first { it !is NetworkResult.Loading }
                }
                when (result) {
                    is NetworkResult.Success -> Loaded(result.data.artwork(), final = true)
                    is NetworkResult.Error -> Loaded(null, final = result.code != null)
                    NetworkResult.Loading -> Loaded(null, final = false)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Loaded(null, final = false)
        }

    private fun Meta.artwork(): Artwork? {
        val artwork = Artwork(
            name = name.takeIf { it.isNotBlank() },
            poster = poster?.takeIf { it.isNotBlank() },
            background = background?.takeIf { it.isNotBlank() },
            logo = logo?.takeIf { it.isNotBlank() },
            landscapePoster = landscapePoster?.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            imdbRating = imdbRating,
            genres = genres
        )
        return artwork.takeIf { it.poster != null || it.background != null || it.logo != null }
    }

    private fun MetaPreview.with(artwork: Artwork): MetaPreview = copy(
        name = artwork.name ?: name,
        poster = artwork.poster ?: poster,
        rawPosterUrl = if (artwork.poster != null) null else rawPosterUrl,
        background = artwork.background ?: background,
        logo = artwork.logo ?: logo,
        landscapePoster = artwork.landscapePoster ?: landscapePoster,
        description = artwork.description ?: description,
        imdbRating = artwork.imdbRating ?: imdbRating,
        genres = artwork.genres.ifEmpty { genres }
    )

    private companion object {
        const val CALLER = "server-art"
        // Home loads every server row in the background, often thousands of titles. A cap below
        // that evicts rows still on screen and asks the addons again on the next Home refresh.
        const val MAX_ENTRIES = 2000
        const val MAX_CONCURRENT_FETCHES = 4
        const val MAX_QUEUED = 200
        const val HIT_TTL_MS = 45 * 60 * 1000L
        const val MISS_TTL_MS = 10 * 60 * 1000L
        const val RETRY_TTL_MS = 60 * 1000L
    }
}
