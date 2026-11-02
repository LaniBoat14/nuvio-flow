package com.nuvio.tv.ui.screens.detail

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryToggleTimeoutTest {
    @Test
    fun `a toggle that never finishes fails with the library message after twenty seconds`() = runTest {
        var cancelled = false
        val toggle = async {
            runCatching {
                withLibraryToggleTimeout({ "Failed to update library" }) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
        }
        runCurrent()
        advanceTimeBy(LIBRARY_TOGGLE_TIMEOUT_MS - 1)
        assertFalse(toggle.isCompleted)
        advanceTimeBy(2)
        val error = toggle.await().exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("Failed to update library", error?.message)
        assertTrue(cancelled)
    }

    @Test
    fun `a toggle that finishes in time returns its result`() = runTest {
        val result = withLibraryToggleTimeout({ "Failed to update library" }) {
            delay(LIBRARY_TOGGLE_TIMEOUT_MS - 1)
            "done"
        }
        assertEquals("done", result)
    }

    @Test
    fun `errors from the toggle itself are passed on unchanged`() = runTest {
        val error = runCatching {
            withLibraryToggleTimeout<String>({ "Failed to update library" }) { throw IllegalArgumentException("Not allowed") }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("Not allowed", error?.message)
    }
}
