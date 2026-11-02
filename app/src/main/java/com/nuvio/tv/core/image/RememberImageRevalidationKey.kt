package com.nuvio.tv.core.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Returns a key that increments when [imageUrl] gets a fresh version
 * via background revalidation. Use as part of memoryCacheKey to force reload.
 */
@Composable
fun rememberImageRevalidationKey(imageUrl: String?): Int {
    if (imageUrl == null) return 0
    return remember(imageUrl) { RevalidationWatch(imageUrl) }.version.intValue
}

/** One listener on [ImageInvalidationBus] for every image on screen, instead of one job per image. */
private class RevalidationWatch(private val url: String) : RememberObserver {
    val version: MutableIntState = mutableIntStateOf(0)

    override fun onRemembered() {
        watches.getOrPut(url) { ArrayList(1) }.add(this)
        if (listener == null) {
            listener = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                ImageInvalidationBus.events.collect { invalidated ->
                    watches[invalidated]?.forEach { it.version.intValue++ }
                }
            }
        }
    }

    override fun onForgotten() {
        val list = watches[url] ?: return
        list.remove(this)
        if (list.isEmpty()) watches.remove(url)
    }

    override fun onAbandoned() = Unit

    private companion object {
        // Main thread only: changed from composition callbacks and the listener on Main.
        val watches = HashMap<String, ArrayList<RevalidationWatch>>()
        var listener: kotlinx.coroutines.Job? = null
    }
}
