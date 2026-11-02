package com.nuvio.tv.core.calendar

import android.content.Context
import com.nuvio.tv.data.local.DeviceUiPreferenceStore
import com.nuvio.tv.domain.model.VisualQualityMode
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import com.nuvio.tv.ui.v2.quality.VisualQualityTier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A small heap, or Performance chosen or assessed for the box, counts as weak. */
fun calendarRefreshConcurrency(lowRamTier: Boolean, mode: VisualQualityMode?, automaticTier: String?): Int {
    val weak = lowRamTier || mode == VisualQualityMode.PERFORMANCE || automaticTier == VisualQualityTier.PERFORMANCE.name
    return if (weak) 2 else 4
}

@Singleton
class CalendarDeviceTier @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun refreshConcurrency(): Int {
        val preferences = withTimeoutOrNull(2_000L) { DeviceUiPreferenceStore.flow(context).first() }
            ?: return calendarRefreshConcurrency(true, null, null)
        return calendarRefreshConcurrency(MemoryBudget.isLowRamTier, preferences.visualQualityMode, preferences.automaticQualityTier)
    }
}
