package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.WatchedTitlesMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCatalogSettingsSyncServiceTest {
    @Test
    fun sharedSettingsMergePreservesUnknownRemoteFields() {
        val remote = buildJsonObject {
            put("future_setting", "preserved")
            put("hide_unreleased_content", false)
        }
        val local = buildJsonObject {
            put("hide_unreleased_content", true)
        }

        val merged = mergeHomeCatalogSettingsJson(remote, local)

        assertEquals("preserved", merged.getValue("future_setting").jsonPrimitive.content)
        assertEquals(true, merged.getValue("hide_unreleased_content").jsonPrimitive.content.toBoolean())
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun watchedTitlesFromAnotherDeviceWinWhenTheBlobCarriesThem() {
        val remote = buildJsonObject {
            put("watched_titles_mode", "HIDE")
            put("items", buildJsonArray { })
        }
        val local = SyncHomeCatalogPayload(watchedTitlesMode = WatchedTitlesMode.DIM.name)

        val decoded = decodeHomeCatalogPayloadPreservingLocal(json, remote, local)

        assertEquals(WatchedTitlesMode.HIDE.name, decoded.watchedTitlesMode)
    }

    @Test
    fun aBlobWithoutWatchedTitlesKeepsTheLocalChoice() {
        val remote = buildJsonObject {
            put("hide_unreleased_content", true)
            put("items", buildJsonArray { })
        }
        val local = SyncHomeCatalogPayload(watchedTitlesMode = WatchedTitlesMode.HIDE.name)

        val decoded = decodeHomeCatalogPayloadPreservingLocal(json, remote, local)

        assertEquals(WatchedTitlesMode.HIDE.name, decoded.watchedTitlesMode)
        assertEquals(true, decoded.hideUnreleasedContent)
    }

    @Test
    fun unknownWatchedTitlesValuesFallBackToShow() {
        assertEquals(WatchedTitlesMode.SHOW, WatchedTitlesMode.fromStored("SOMETHING_NEW"))
        assertEquals(WatchedTitlesMode.SHOW, WatchedTitlesMode.fromStored(null))
        assertEquals(WatchedTitlesMode.DIM, WatchedTitlesMode.fromStored("dim"))
    }
}
