package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionsChoiceTest {

    @Test
    fun `custom network off is Automatic whatever the other two say`() {
        assertEquals(ConnectionsChoice.AUTOMATIC, ConnectionsChoice.from(false, false, 2))
        assertEquals(ConnectionsChoice.AUTOMATIC, ConnectionsChoice.from(false, true, 6))
    }

    @Test
    fun `custom network on without parallel connections is one`() {
        assertEquals(1, ConnectionsChoice.from(true, false, 4))
    }

    @Test
    fun `custom network on with parallel connections is the count`() {
        assertEquals(2, ConnectionsChoice.from(true, true, 2))
        assertEquals(5, ConnectionsChoice.from(true, true, 5))
        assertEquals(2, ConnectionsChoice.from(true, true, 0))
    }

    @Test
    fun `the choices run from Automatic and one up to the most connections`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8), ConnectionsChoice.values(8))
        assertEquals(listOf(0, 1, 2), ConnectionsChoice.values(1))
    }
}
