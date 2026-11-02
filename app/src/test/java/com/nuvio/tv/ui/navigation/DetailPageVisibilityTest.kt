package com.nuvio.tv.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPageVisibilityTest {
    private val detail = "detail/tt0413573/series"

    @Test fun `details still drawn after going back to home counts as leaving`() {
        assertTrue(isDetailPageLeaving("home", listOf("home", detail)))
    }

    @Test fun `home on its own does not`() {
        assertFalse(isDetailPageLeaving("home", listOf("home")))
    }

    @Test fun `staying on details or opening it does not`() {
        assertFalse(isDetailPageLeaving(detail, listOf(detail)))
        assertFalse(isDetailPageLeaving(detail, listOf("home", detail)))
    }

    @Test fun `other pages are not affected`() {
        assertFalse(isDetailPageLeaving("home", listOf("home", "settings")))
        assertFalse(isDetailPageLeaving(null, emptyList()))
    }
}
