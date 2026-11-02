package com.nuvio.tv.ui.screens.settings

import android.content.Context
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchedTitlesMode
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LayoutSettingsHomeContentSyncTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val store = mockk<LayoutPreferenceDataStore>(relaxed = true)
    private val homeCatalogSync = mockk<HomeCatalogSettingsSyncService>(relaxed = true)

    @Test
    fun `a new watched titles choice also goes into the shared Home content settings`() = runTest {
        val viewModel = createViewModel()

        viewModel.onEvent(LayoutSettingsEvent.SetWatchedTitlesMode(WatchedTitlesMode.HIDE))
        runCurrent()

        coVerifyOrder {
            store.setWatchedTitlesMode(WatchedTitlesMode.HIDE)
            homeCatalogSync.triggerPush()
        }
    }

    @Test
    fun `a new hide unreleased choice also goes into the shared Home content settings`() = runTest {
        val viewModel = createViewModel()

        viewModel.onEvent(LayoutSettingsEvent.SetHideUnreleasedContent(true))
        runCurrent()

        coVerifyOrder {
            store.setHideUnreleasedContent(true)
            homeCatalogSync.triggerPush()
        }
    }

    private fun TestScope.createViewModel(): LayoutSettingsViewModel {
        val context = mockk<Context>(relaxed = true) {
            every { resources } throws IllegalStateException("no resources in unit tests")
        }
        val viewModel = LayoutSettingsViewModel(
            context = context,
            layoutPreferenceDataStore = store,
            streamBadgeSettingsDataStore = mockk(relaxed = true),
            traktSettingsDataStore = mockk(relaxed = true),
            trailerSettingsDataStore = mockk(relaxed = true),
            addonRepository = mockk(relaxed = true),
            metaRepository = mockk(relaxed = true),
            homeCatalogSettingsSyncService = homeCatalogSync
        )
        runCurrent()
        return viewModel
    }
}
