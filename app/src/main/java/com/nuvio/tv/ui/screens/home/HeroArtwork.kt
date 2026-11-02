package com.nuvio.tv.ui.screens.home

import java.lang.ref.SoftReference

/** Every fallback belongs to this focused carousel item; never borrow row/global artwork. */
internal data class HeroArtworkSelection(val ownerKey: String?, val urls: List<String>)

internal fun heroArtworkSelection(ownerKey: String?, vararg urls: String?): HeroArtworkSelection =
    HeroArtworkSelection(ownerKey, if (ownerKey == null) emptyList() else
        urls.mapNotNull { it?.takeIf(String::isNotBlank) }.distinct())

internal data class HeroArtworkTicket(val ownerKey: String?, val url: String, val generation: Long)

/** A decoded image may survive enrichment of its own title, but never a focus change. */
internal data class HeroArtworkLoadState<T>(
    val selection: HeroArtworkSelection,
    val generation: Long = 0,
    val candidateIndex: Int = 0,
    val ready: T? = null
) {
    val ticket: HeroArtworkTicket?
        get() = selection.urls.getOrNull(candidateIndex)?.let {
            HeroArtworkTicket(selection.ownerKey, it, generation)
        }

    fun select(next: HeroArtworkSelection): HeroArtworkLoadState<T> = if (next == selection) this else
        HeroArtworkLoadState(next, generation + 1, ready = ready.takeIf {
            next.ownerKey == selection.ownerKey && next.urls.isNotEmpty()
        })

    fun loaded(request: HeroArtworkTicket, image: T): HeroArtworkLoadState<T> =
        if (request == ticket) copy(ready = image) else this

    fun failed(request: HeroArtworkTicket): HeroArtworkLoadState<T> =
        if (request == ticket) copy(candidateIndex = candidateIndex + 1) else this
}

/**
 * The hero image last on screen, held softly. Coming back to Home draws it straight away
 * instead of fading in from an empty backdrop while the cache is asked again.
 */
internal class ShownHeroMemory<T : Any> {
    private class Entry<T : Any>(val url: String, val width: Int, val height: Int, val image: SoftReference<T>)

    @Volatile private var entry: Entry<T>? = null

    fun keep(url: String, width: Int, height: Int, image: T) {
        entry = Entry(url, width, height, SoftReference(image))
    }

    fun recall(url: String, width: Int, height: Int): T? =
        entry?.takeIf { it.url == url && it.width == width && it.height == height }?.image?.get()
}

internal val shownHeroImage = ShownHeroMemory<coil3.Image>()

internal data class ResolvedModernHeroState(
    val artwork: HeroArtworkSelection,
    val preview: HeroPreview?,
    val enrichmentActive: Boolean
)
