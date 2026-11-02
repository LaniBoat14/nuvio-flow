package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The cursor of a held seek. The controller moves it every frame; the bar, the time and the thumbnail strip read it
 * directly so only they redraw.
 */
internal object ScrubGlide {
    var active by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    /** Film ms per ms. */
    var speed by mutableFloatStateOf(0f)

    fun stop() {
        active = false
        speed = 0f
    }
}
