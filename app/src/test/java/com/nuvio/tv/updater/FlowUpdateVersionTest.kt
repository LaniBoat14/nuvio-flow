package com.nuvio.tv.updater

import org.junit.Assert.*
import org.junit.Test

class FlowUpdateVersionTest {
    @Test fun revisionsOnTheSameBaseAdvance() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.8.flow.5", "1.1.0-beta.8.flow.4"))
        assertFalse(VersionUtils.isRemoteNewer("1.1.0-beta.8.flow.4", "1.1.0-beta.8.flow.4"))
    }
    @Test fun upstreamBetaAdvancesAndStableRemainsNewer() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.9.flow.1", "1.1.0-beta.8.flow.4"))
        assertTrue(VersionUtils.isRemoteNewer("1.1.0", "1.1.0-beta.9.flow.1"))
        assertFalse(VersionUtils.isRemoteNewer("1.1.0-beta.8.flow.99", "1.1.0-beta.9.flow.1"))
    }
}
