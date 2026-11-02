package com.nuvio.tv.core.build

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportsTest {
    @Test fun `blank or missing report server means reports are unavailable`() {
        assertFalse(IssueReports.isConfigured(null))
        assertFalse(IssueReports.isConfigured(""))
        assertFalse(IssueReports.isConfigured("   "))
    }

    @Test fun `a configured report server brings reports back`() {
        assertTrue(IssueReports.isConfigured("https://reports.example.org/"))
    }
}
