package com.nuvio.tv.ui.screens.home

/** Incoming work is owned by a generation; outgoing pixels are only a presentation layer. */
internal data class HeroSceneTicket(val identity: String?, val generation: Long)

internal data class HeroScenePresentation<T>(
    val ticket: HeroSceneTicket = HeroSceneTicket(null, 0),
    val displayed: T? = null,
    val committed: Boolean = false,
    val displayedTicket: HeroSceneTicket? = null
) {
    fun select(identity: String?): HeroScenePresentation<T> =
        if (identity == ticket.identity) this else copy(
            ticket = HeroSceneTicket(identity, ticket.generation + 1), committed = false
        )

    fun ready(request: HeroSceneTicket, scene: T): HeroScenePresentation<T> =
        if (request != ticket || committed) this else copy(displayed = scene, committed = true, displayedTicket = request)

    /** A bounded neutral fallback may later upgrade to the first successfully decoded backdrop. */
    fun fallback(request: HeroSceneTicket, scene: T): HeroScenePresentation<T> =
        if (request != ticket || committed) this else copy(displayed = scene, displayedTicket = request)

    /** The safety timer only clears a scene left over from another title. */
    fun timeout(request: HeroSceneTicket, scene: T): HeroScenePresentation<T> =
        if (displayedTicket == request) this else fallback(request, scene)

    fun revise(request: HeroSceneTicket, transform: (T) -> T): HeroScenePresentation<T> =
        if (request != ticket || displayed == null) this else copy(displayed = transform(displayed))
}

/** URL roles are resolved before image loading; only a poster the add-on marks landscape is a last candidate. */
internal fun landscapeHeroArtwork(
    owner: String?, poster: String?, background: String?, landscape: String?,
    fallbackBackground: String? = null, fallbackPoster: String? = null, widePoster: String? = null
): HeroArtworkSelection = heroArtworkSelection(owner,
    background?.takeUnless { it == poster || it == fallbackPoster },
    landscape?.takeUnless { it == poster || it == fallbackPoster },
    fallbackBackground?.takeUnless { it == poster || it == fallbackPoster },
    widePoster
)

internal fun heroTransferUrl(url: String, width: Int): String {
    if (!url.startsWith("https://image.tmdb.org/t/p/")) return url
    val prefix = "https://image.tmdb.org/t/p/"
    val path = url.removePrefix(prefix).substringAfter('/', "")
    if (path.isBlank()) return url
    val size = when { width <= 780 -> "w780"; width <= 1600 -> "w1280"; else -> "original" }
    return "$prefix$size/$path"
}

internal fun canWarmDecodedHero(lowRam: Boolean, totalRam: Long, cacheBytes: Long, width: Int, height: Int): Boolean {
    val bytes = width.toLong() * height * 4
    // Current + outgoing + the two bounded neighbours must fit without expanding the cache.
    return !lowRam && totalRam >= 3_300L * 1024 * 1024 && bytes > 0 && bytes * 4 <= cacheBytes / 4
}
