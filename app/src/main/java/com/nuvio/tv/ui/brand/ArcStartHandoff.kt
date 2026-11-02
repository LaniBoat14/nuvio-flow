package com.nuvio.tv.ui.brand

/**
 * The start window on Android 8 to 11 shows the launcher mark where the entrance puts its logo, so the entrance can
 * begin from it: the logo is there from the first frame and the whole frame fades in over the window's picture.
 */
object ArcStartHandoff {
    /** The stage and the mark settle from the window's colours into the theme's together, over this long. */
    const val CrossfadeSeconds = 0.3f
    private const val PlateFadeSeconds = 0.9f

    /** Android 12 and newer draw their own splash icon, and Android 7 cannot place the mark by fractions. */
    fun windowCarriesMark(sdkInt: Int): Boolean = sdkInt in 26..30

    /** Opacity of the whole frame [elapsed] seconds after the first one. */
    fun frameAlpha(elapsed: Float, handoff: Boolean, reducedMotion: Boolean): Float =
        if (handoff || reducedMotion) ArcIgnitionScene.smooth(elapsed / CrossfadeSeconds) else 1f

    /** Strength of the logo plate at intro time [clock]; on a hand-off it is already there. */
    fun plateFade(clock: Float, handoff: Boolean): Float =
        if (handoff) 1f else ArcIgnitionScene.smooth(clock / PlateFadeSeconds)
}
