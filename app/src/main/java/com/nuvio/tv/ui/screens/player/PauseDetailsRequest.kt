package com.nuvio.tv.ui.screens.player

/** The pause overlay opened by a remote button: it stays up while paused and may play the video again when closed. */
internal class PauseDetailsRequest {
    var isOpen = false
        private set
    private var resumeOnClose = false

    fun open(resumeOnClose: Boolean) {
        isOpen = true
        this.resumeOnClose = resumeOnClose
    }

    /** True when the video should play again. */
    fun close(): Boolean {
        val resume = isOpen && resumeOnClose
        clear()
        return resume
    }

    fun clear() {
        isOpen = false
        resumeOnClose = false
    }

    /** True while an open request keeps the overlay up; a request whose overlay went away is forgotten. */
    fun holdsOverlay(overlayShowing: Boolean): Boolean {
        if (isOpen && !overlayShowing) clear()
        return isOpen
    }
}
