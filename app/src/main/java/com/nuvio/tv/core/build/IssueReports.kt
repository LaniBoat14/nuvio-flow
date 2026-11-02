package com.nuvio.tv.core.build

import com.nuvio.tv.BuildConfig

/** Playback and sign-in reports need a report server set at build time; without one, no report option is shown. */
object IssueReports {
    val available: Boolean get() = isConfigured(BuildConfig.PLAYBACK_REPORTS_BASE_URL)

    internal fun isConfigured(baseUrl: String?): Boolean = !baseUrl.isNullOrBlank()
}
