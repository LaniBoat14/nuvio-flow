package com.nuvio.tv.ui.v2.scale

import com.nuvio.tv.domain.model.DeviceUiPreferences
import com.nuvio.tv.domain.model.UiScaleMode
import com.nuvio.tv.ui.v2.diagnostics.UiCanvasSnapshot
import kotlin.math.roundToInt

/** [calibrationPercent] is the canvas part of an automatic scale, 100 for a manual one. */
data class UiScaleDecision(val percent: Int, val reason: String, val calibrationPercent: Int = 100)

/** Poster size factor: the automatic calibration never shrinks posters below the Original look. */
fun UiScaleDecision.posterSizeFactor(): Float =
    if (calibrationPercent in 1 until 100) 100f / calibrationPercent else 1f

fun UiScaleDecision.posterSizeDp(presetDp: Int): Float = presetDp * posterSizeFactor()

/** Scales the UI against a reference canvas, so geometry matches across boxes. */
class UiScaleResolver(
    private val referenceWidthDp: Float = 1280f,
    private val referenceHeightDp: Float = 720f
) {
    init {
        require(referenceWidthDp.isFinite() && referenceWidthDp > 0)
        require(referenceHeightDp.isFinite() && referenceHeightDp > 0)
    }

    fun resolve(canvas: UiCanvasSnapshot, preferences: DeviceUiPreferences): UiScaleDecision {
        if (preferences.uiScaleMode == UiScaleMode.MANUAL) {
            return UiScaleDecision(preferences.manualUiScalePercent.coerceIn(75, 115), "Manual")
        }
        if (!canvas.hasValidGeometry) return UiScaleDecision(100, "Waiting for valid window geometry")
        val raw = minOf(
            canvas.logicalWidthDp!! / referenceWidthDp,
            canvas.logicalHeightDp!! / referenceHeightDp
        ).coerceIn(0.75f, 1.15f)
        val fineTune = 1f + preferences.autoScaleFineTunePercent.coerceIn(-10, 10) / 100f
        return UiScaleDecision(
            (raw * fineTune * 100).roundToInt().coerceIn(72, 120),
            "Logical canvas (${referenceWidthDp.toInt()} x ${referenceHeightDp.toInt()} dp reference)",
            calibrationPercent = (raw * 100).roundToInt()
        )
    }
}
