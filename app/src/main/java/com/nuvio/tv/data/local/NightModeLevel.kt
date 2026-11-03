package com.nuvio.tv.data.local

enum class NightModeLevel(val key: String) {
    OFF("off"),
    LIGHT("light"),
    MEDIUM("medium"),
    STRONG("strong");

    companion object {
        val DEFAULT_ON = MEDIUM

        fun fromKey(key: String?): NightModeLevel = entries.firstOrNull { it.key == key } ?: OFF

        fun lastOnFromKey(key: String?): NightModeLevel =
            fromKey(key).takeIf { it != OFF } ?: DEFAULT_ON
    }
}

/**
 * What one press of a remote button set to night mode does.
 * [TOGGLE]: Off goes back to the level used last, any level goes to Off.
 * [CYCLE]: Off, Light, Medium, Strong, then Off again.
 */
enum class NightModeKeyStyle { TOGGLE, CYCLE }

val NIGHT_MODE_KEY_STYLE = NightModeKeyStyle.TOGGLE

fun NightModeLevel.nextForKey(
    lastOn: NightModeLevel,
    style: NightModeKeyStyle = NIGHT_MODE_KEY_STYLE
): NightModeLevel = when (style) {
    NightModeKeyStyle.TOGGLE -> if (this == NightModeLevel.OFF) {
        lastOn.takeIf { it != NightModeLevel.OFF } ?: NightModeLevel.DEFAULT_ON
    } else {
        NightModeLevel.OFF
    }
    NightModeKeyStyle.CYCLE -> NightModeLevel.entries[(ordinal + 1) % NightModeLevel.entries.size]
}

/** The level a toggle brings back after [next] replaces [current]. */
fun rememberedNightModeLevel(
    current: NightModeLevel,
    next: NightModeLevel,
    lastOn: NightModeLevel
): NightModeLevel = when {
    next != NightModeLevel.OFF -> next
    current != NightModeLevel.OFF -> current
    else -> lastOn.takeIf { it != NightModeLevel.OFF } ?: NightModeLevel.DEFAULT_ON
}
