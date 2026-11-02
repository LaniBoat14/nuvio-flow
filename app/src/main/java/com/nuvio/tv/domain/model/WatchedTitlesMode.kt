package com.nuvio.tv.domain.model

enum class WatchedTitlesMode {
    SHOW,
    DIM,
    HIDE;

    companion object {
        fun fromStored(value: String?): WatchedTitlesMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SHOW
    }
}
