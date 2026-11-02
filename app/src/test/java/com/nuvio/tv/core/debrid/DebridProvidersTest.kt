package com.nuvio.tv.core.debrid

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.DebridSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridProvidersTest {
    @Test
    fun `configured services only include visible providers`() {
        val settings = DebridSettings(
            enabled = true,
            torboxApiKey = "tb",
            realDebridApiKey = "rd"
        )

        val services = DebridProviders.configuredServices(settings)

        assertEquals(listOf(DebridProviders.Torbox), services.map { it.provider })
        assertTrue(DebridProviders.isVisible(DebridProviders.TORBOX_ID))
        assertFalse(DebridProviders.isVisible(DebridProviders.REAL_DEBRID_ID))
    }

    @Test
    fun `premiumize uses the api key dialog when the build has no client id`() {
        assertEquals(DebridProviderAuthMethod.ApiKey, DebridProviders.premiumizeAuthMethod(""))
        assertEquals(DebridProviderAuthMethod.ApiKey, DebridProviders.premiumizeAuthMethod("   "))
    }

    @Test
    fun `premiumize uses device sign-in when the build has a client id`() {
        assertEquals(DebridProviderAuthMethod.DeviceCode, DebridProviders.premiumizeAuthMethod("nuvio-client"))
    }

    @Test
    fun `premiumize follows the build client id and torbox stays on device sign-in`() {
        assertEquals(
            DebridProviders.premiumizeAuthMethod(BuildConfig.PREMIUMIZE_CLIENT_ID),
            DebridProviders.Premiumize.authMethod
        )
        assertEquals(DebridProviderAuthMethod.DeviceCode, DebridProviders.Torbox.authMethod)
        assertEquals(DebridProviderAuthMethod.ApiKey, DebridProviders.RealDebrid.authMethod)
    }
}
