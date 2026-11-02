package com.nuvio.tv.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedUpdateVersionTest {

    @Test
    fun `a rebuild under the same name with a higher code is still waiting to install`() {
        val download = DownloadedUpdate("v1.1.0-nt.8", "/updates/a.apk", versionCode = 1530L)
        assertTrue(download.isNewerThanInstalled(installedName = "1.1.0-nt.8", installedCode = 1528L))
    }

    @Test
    fun `the same code is installed already even when the name would say newer`() {
        val download = DownloadedUpdate("v1.1.0-nt.9", "/updates/a.apk", versionCode = 1530L)
        assertFalse(download.isNewerThanInstalled(installedName = "1.1.0-nt.8", installedCode = 1530L))
    }

    @Test
    fun `a download saved without a code is judged by name`() {
        val download = DownloadedUpdate("v1.1.0-nt.8", "/updates/a.apk")
        assertFalse(download.isNewerThanInstalled(installedName = "1.1.0-nt.8", installedCode = 1528L))
        assertTrue(DownloadedUpdate("v1.1.0-nt.9", "/updates/a.apk").isNewerThanInstalled("1.1.0-nt.8", 1528L))
    }
}
