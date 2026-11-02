package com.nuvio.tv.ui.v2.scale

import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.scaledFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PosterSizingTest {
    private val presets = listOf(104, 112, 120, 126, 134, 140, 176)

    @Test
    fun `automatic calibration below 100 keeps the Original look poster size`() {
        val calibrated = UiScaleDecision(75, "Logical canvas", calibrationPercent = 75)
        val expected = listOf(138.67f, 149.33f, 160f, 168f, 178.67f, 186.67f, 234.67f)
        presets.zip(expected).forEach { (preset, dp) ->
            assertEquals(dp, calibrated.posterSizeDp(preset), 0.01f)
            // The chosen width ends up at the same pixel count as the Original look at 100 percent.
            assertEquals(preset.toFloat(), calibrated.posterSizeDp(preset) * 0.75f, 0.01f)
        }
        assertEquals(186.67f, UiScaleDecision(82, "Logical canvas", calibrationPercent = 75).posterSizeDp(140), 0.01f)
        assertEquals(280f, UiScaleDecision(50, "Logical canvas", calibrationPercent = 50).posterSizeDp(140), 0.01f)
    }

    @Test
    fun `a calibration at or above 100 and a manual or Original scale leave the preset alone`() {
        val untouched = listOf(
            UiScaleDecision(100, "Logical canvas", calibrationPercent = 100),
            UiScaleDecision(115, "Logical canvas", calibrationPercent = 115),
            UiScaleDecision(75, "Manual"),
            UiScaleDecision(115, "Manual"),
            UiScaleDecision(85, "Original Nuvio"),
            UiScaleDecision(100, "Waiting for valid window geometry")
        )
        for (decision in untouched) {
            presets.forEach { preset -> assertEquals(preset.toFloat(), decision.posterSizeDp(preset), 0f) }
        }
    }

    @Test
    fun `a broken calibration is treated as no calibration`() {
        assertEquals(140f, UiScaleDecision(100, "x", calibrationPercent = 0).posterSizeDp(140), 0f)
        assertEquals(140f, UiScaleDecision(100, "x", calibrationPercent = -5).posterSizeDp(140), 0f)
        assertEquals(1f, UiScaleDecision(100, "x", calibrationPercent = 0).posterSizeFactor(), 0f)
    }

    @Test
    fun `fixed poster sizes follow the same rule as the chosen width`() {
        val calibrated = UiScaleDecision(75, "Logical canvas", calibrationPercent = 75)
        val fixedSizes = listOf(
            PosterCardDefaults.Style to (168f to 252f),
            PosterCardStyle(width = 112.dp, height = 168.dp) to (149.33f to 224f),
            PosterCardStyle(width = 112.dp, height = 240.dp) to (149.33f to 320f),
            PosterCardStyle(width = 260.dp, height = 146.dp, focusedBorderWidth = 4.dp) to (346.67f to 194.67f)
        )
        for ((style, size) in fixedSizes) {
            val scaled = style.scaledFor(calibrated)
            assertEquals(size.first, scaled.width.value, 0.01f)
            assertEquals(size.second, scaled.height.value, 0.01f)
            assertEquals(style.cornerRadius, scaled.cornerRadius)
            assertEquals(style.focusedBorderWidth, scaled.focusedBorderWidth)
            assertEquals(style.focusedScale, scaled.focusedScale, 0f)
            assertEquals(style.aspectRatio, scaled.aspectRatio, 0.0001f)
            // Same pixel count as the Original look at 100 percent.
            assertEquals(style.width.value, scaled.width.value * calibrated.calibrationPercent / 100f, 0.01f)
        }
        // Search and the other fixed screens match Home at the default Balanced width.
        assertEquals(calibrated.posterSizeDp(126), PosterCardDefaults.Style.scaledFor(calibrated).width.value, 0.01f)
    }

    @Test
    fun `fixed poster sizes stay as they are without an automatic calibration`() {
        val untouched = listOf(
            UiScaleDecision(100, "Logical canvas", calibrationPercent = 100),
            UiScaleDecision(115, "Logical canvas", calibrationPercent = 115),
            UiScaleDecision(75, "Manual"),
            UiScaleDecision(115, "Manual"),
            UiScaleDecision(85, "Original Nuvio"),
            UiScaleDecision(100, "Waiting for valid window geometry")
        )
        val style = PosterCardStyle(width = 260.dp, height = 146.dp, cornerRadius = 8.dp)
        for (decision in untouched) {
            assertSame(style, style.scaledFor(decision))
            assertSame(PosterCardDefaults.Style, PosterCardDefaults.Style.scaledFor(decision))
        }
    }
}
