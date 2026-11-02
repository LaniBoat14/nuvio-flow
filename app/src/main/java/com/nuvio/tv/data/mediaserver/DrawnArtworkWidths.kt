package com.nuvio.tv.data.mediaserver

/**
 * The widths, in screen pixels, Home draws a poster card, a backdrop (the hero, full width) and a hero logo at, with
 * the UI scale applied. Servers that size their artwork per request ask for these instead of a fixed size.
 */
data class DrawnArtworkWidths(val poster: Int, val backdrop: Int, val logo: Int) {
    companion object {
        const val MAX_BACKDROP = 2560

        @Volatile
        var current: DrawnArtworkWidths? = null
            private set

        fun report(posterPx: Int, logoPx: Int, screenWidthPx: Int) {
            if (screenWidthPx <= 0 || posterPx <= 0 || logoPx <= 0) return
            val widths = of(posterPx, logoPx, screenWidthPx)
            if (widths != current) current = widths
        }

        fun of(posterPx: Int, logoPx: Int, screenWidthPx: Int): DrawnArtworkWidths = DrawnArtworkWidths(
            poster = posterPx.coerceIn(1, screenWidthPx),
            backdrop = screenWidthPx.coerceAtMost(MAX_BACKDROP),
            logo = logoPx.coerceIn(1, screenWidthPx)
        )
    }
}
