package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.ExperienceMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ExperienceModeDefaultTest {
    @Test
    fun `nothing chosen yet starts on advanced`() {
        assertEquals(ExperienceMode.ADVANCED, resolveExperienceMode(null, essentialChosen = false))
    }

    @Test
    fun `essential from the old first run screen moves to advanced`() {
        assertEquals(ExperienceMode.ADVANCED, resolveExperienceMode("ESSENTIAL", essentialChosen = false))
    }

    @Test
    fun `essential picked from settings stays essential`() {
        assertEquals(ExperienceMode.ESSENTIAL, resolveExperienceMode("ESSENTIAL", essentialChosen = true))
    }

    @Test
    fun `advanced stays advanced`() {
        assertEquals(ExperienceMode.ADVANCED, resolveExperienceMode("ADVANCED", essentialChosen = false))
        assertEquals(ExperienceMode.ADVANCED, resolveExperienceMode("ADVANCED", essentialChosen = true))
    }

    @Test
    fun `unknown stored value falls back to advanced`() {
        assertEquals(ExperienceMode.ADVANCED, resolveExperienceMode("SOMETHING_ELSE", essentialChosen = true))
    }
}
