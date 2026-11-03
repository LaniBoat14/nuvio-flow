package com.nuvio.tv.ui.screens.settings

/**
 * The Connections setting over the stored keys: Automatic is Custom network off, 1 is Custom network on with one
 * connection, and 2 or more is Custom network on with that many parallel connections.
 */
internal object ConnectionsChoice {
    const val AUTOMATIC = 0

    fun from(customNetwork: Boolean, parallel: Boolean, count: Int): Int = when {
        !customNetwork -> AUTOMATIC
        !parallel -> 1
        else -> count.coerceAtLeast(2)
    }

    fun values(maxConnections: Int): List<Int> = (AUTOMATIC..maxConnections.coerceAtLeast(2)).toList()
}
