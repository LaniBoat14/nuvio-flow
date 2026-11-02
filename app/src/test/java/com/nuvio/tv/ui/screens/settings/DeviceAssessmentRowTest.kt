package com.nuvio.tv.ui.screens.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.nuvio.tv.core.assessment.AssessmentItem
import com.nuvio.tv.core.assessment.AssessmentTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAssessmentRowTest {
    private val yourCall = "Your call"
    private val leaveAsIs = "Leave as is"

    private fun item(current: String?, suggested: String, changeNeeded: Boolean) = AssessmentItem(
        key = "vod_cache",
        title = "VOD Disk Cache",
        currentValue = current,
        recommendedValue = suggested,
        grounds = "",
        tier = AssessmentTier.CALCULATED,
        changeNeeded = changeNeeded
    )

    @Test
    fun `a suggested change shows the real setting underneath`() {
        assertEquals(AssessmentRowFooter.CURRENTLY,
            assessmentRowFooter(item("Off", "On · Auto, up to ≈10.0 GB", true), yourCall, leaveAsIs))
    }

    @Test
    fun `a setting that already matches gets the tick`() {
        assertEquals(AssessmentRowFooter.ALREADY_SET,
            assessmentRowFooter(item("Automatic", "Automatic (follows Automatic speed-up)", false), yourCall, leaveAsIs))
    }

    @Test
    fun `advice-only rows show neither`() {
        assertEquals(AssessmentRowFooter.NONE, assessmentRowFooter(item("On", yourCall, false), yourCall, leaveAsIs))
        assertEquals(AssessmentRowFooter.NONE, assessmentRowFooter(item("Off", leaveAsIs, false), yourCall, leaveAsIs))
        assertEquals(AssessmentRowFooter.NONE, assessmentRowFooter(item(null, "Off", true), yourCall, leaveAsIs))
    }

    @Test
    fun `only the suggestion label is quieter, the value keeps the row style`() {
        val value = "On · Auto, up to ≈10.0 GB"
        val grey = Color(0xFF888888)
        val text = suggestedValueText("Suggested: $value", value, grey)
        assertEquals("Suggested: $value", text.text)
        assertEquals(1, text.spanStyles.size)
        val label = text.spanStyles.single()
        assertEquals(0, label.start)
        assertEquals("Suggested: ".length, label.end)
        assertEquals(grey, label.item.color)
        assertEquals(FontWeight.Normal, label.item.fontWeight)
    }

    @Test
    fun `a label after the value is styled too`() {
        val text = suggestedValueText("Automatic : 建议", "Automatic", Color.Gray)
        assertEquals(1, text.spanStyles.size)
        assertEquals("Automatic".length, text.spanStyles.single().start)
        assertEquals(text.text.length, text.spanStyles.single().end)
    }

    @Test
    fun `an unexpected translation falls back to plain text`() {
        val text = suggestedValueText("Suggested", "Off", Color.Gray)
        assertEquals("Suggested", text.text)
        assertTrue(text.spanStyles.isEmpty())
    }
}
