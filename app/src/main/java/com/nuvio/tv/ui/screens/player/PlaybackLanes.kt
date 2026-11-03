package com.nuvio.tv.ui.screens.player

import java.util.Locale

/** Downloads the playing stream uses at once, for the stats HUD. */
internal data class PlaybackLanes(val connections: Int, val chunkBytes: Long, val auto: Boolean) {
    /** Null when there is only one download, so a line that adds it stays as it was. */
    val label: String?
        get() {
            if (connections <= 1 || chunkBytes <= 0L) return null
            val auto = if (auto) " · auto" else ""
            return "$connections × ${chunkSizeLabel(chunkBytes)}$auto"
        }

    companion object {
        private const val KB = 1024L
        private const val MB = 1024L * 1024L

        @Volatile private var manual: PlaybackLanes? = null
        @Volatile private var auto = false

        fun useManual(connections: Int, chunkBytes: Long) {
            auto = false
            manual = PlaybackLanes(connections, chunkBytes, auto = false)
        }

        fun useAuto() {
            manual = null
            auto = true
        }

        fun clear() {
            manual = null
            auto = false
        }

        fun current(): PlaybackLanes? {
            manual?.let { return it }
            val chunk = AutoSpeedup.pieceBytes
            if (!auto || chunk <= 0L || !AutoSpeedup.active) return null
            val downloads = if (AutoSpeedup.multiOpen.get() > 0) AutoSpeedup.downloads else 1
            return PlaybackLanes(downloads, chunk, auto = true)
        }

        /** The Speed row's text with the downloads added when there is more than one. */
        fun speedText(speed: String, lanes: PlaybackLanes?): String =
            lanes?.label?.let { "$speed · $it" } ?: speed

        /** The Connections row: downloads and chunk size on the chunk path, else a plain single stream. */
        fun connectionsText(lanes: PlaybackLanes?): String {
            if (lanes == null || lanes.chunkBytes <= 0L) return "1 · single stream"
            val auto = if (lanes.auto) " · auto" else ""
            return "${lanes.connections.coerceAtLeast(1)} × ${chunkSizeLabel(lanes.chunkBytes)}$auto"
        }

        /**
         * The Requests row: requests running now of what the server takes at once, what one request carries while data
         * flows and its wait for the first byte; null without Automatic.
         */
        fun requestsText(hud: AutoSpeedup.Hud?): String? {
            if (hud == null) return null
            val parts = mutableListOf("${hud.requests} of ${hud.budget}")
            if (hud.perRequestBps > 0L) {
                parts += String.format(Locale.US, "%.1f Mbit/s each", hud.perRequestBps * 8 / 1_000_000.0)
            }
            if (hud.firstByteMs >= 0L) parts += "first byte ${hud.firstByteMs} ms"
            return parts.joinToString(" · ")
        }

        fun chunkSizeLabel(bytes: Long): String = when {
            bytes < MB -> "${(bytes + KB / 2) / KB} KB"
            bytes % MB == 0L -> "${bytes / MB} MB"
            else -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / MB)
        }
    }
}
