package com.nuvio.tv.ui.screens.detail

import kotlinx.coroutines.withTimeoutOrNull

internal const val LIBRARY_TOGGLE_TIMEOUT_MS = 20_000L

/** Gives up on a library toggle that takes too long, failing with [failureMessage] so the button is freed. */
internal suspend fun <T : Any> withLibraryToggleTimeout(
    failureMessage: () -> String,
    timeoutMs: Long = LIBRARY_TOGGLE_TIMEOUT_MS,
    block: suspend () -> T
): T = withTimeoutOrNull(timeoutMs) { block() } ?: throw IllegalStateException(failureMessage())
