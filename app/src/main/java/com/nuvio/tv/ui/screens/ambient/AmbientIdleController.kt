package com.nuvio.tv.ui.screens.ambient

import com.nuvio.tv.core.player.ScreensaverController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** With Ambient Mode and Dim when idle both on, Ambient Mode runs and the dimmer waits; false swaps them. */
const val AMBIENT_BEFORE_OLED_DIM = true

/**
 * Show/dismiss state for Ambient Mode. The idle clock is the screensaver's, so Ambient Mode
 * and the OLED dimmer count idle time from the same last input.
 */
class AmbientIdleController(private val idleClock: ScreensaverController) {
    val lastInteractionAt: StateFlow<Long>
        get() = idleClock.lastInteractionAt

    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    fun onInteraction() {
        idleClock.notifyInteraction()
    }

    fun show() {
        _isShowing.value = true
    }

    fun dismiss() {
        _isShowing.value = false
        onInteraction()
    }
}
