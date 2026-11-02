package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class CatalogRepositorySearchCacheTest {
    @Test
    fun `a long run of searches does not push a Home row out of memory`() = runTest {
        val response = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(CatalogResponseDto::class.java)
            .fromJson("""{"metas":[{"id":"tt1","type":"movie","name":"A film"}]}""")!!
        val requestedUrls = mutableListOf<String>()
        val api = mockk<AddonApi>()
        coEvery { api.getCatalog(capture(requestedUrls)) } returns Response.success(response)
        val preferences = mockk<LayoutPreferenceDataStore> {
            every { customPosterUrlPattern } returns flowOf("")
            every { customPosterEnabledScreens } returns flowOf(emptySet())
        }
        val repository = CatalogRepositoryImpl(
            mockk<Context>(relaxed = true), api, preferences, healthStore = mockk(relaxed = true),
            serverCatalog = mockk(relaxed = true)
        )
        val baseUrl = "https://search-cache.example.com"
        suspend fun fetch(extraArgs: Map<String, String>) = repository.getCatalog(
            addonBaseUrl = baseUrl,
            addonId = "addon",
            addonName = "Addon",
            catalogId = "top",
            catalogName = "Top",
            type = "movie",
            skip = 0,
            skipStep = 100,
            extraArgs = extraArgs,
            supportsSkip = false
        ).last()

        fetch(mapOf("genre" to "Drama"))
        repeat(80) { fetch(mapOf("search" to "word$it")) }
        fetch(mapOf("genre" to "Drama"))

        assertEquals(1, requestedUrls.count { it.contains("genre=Drama") })
    }
}
