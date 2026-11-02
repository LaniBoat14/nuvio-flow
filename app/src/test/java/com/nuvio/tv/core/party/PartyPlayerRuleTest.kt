package com.nuvio.tv.core.party

import com.nuvio.tv.data.local.PlayerPreference
import org.junit.Assert.assertEquals
import org.junit.Test

class PartyPlayerRuleTest {

    @Test
    fun `without a party the saved choice is kept`() {
        PlayerPreference.entries.forEach { saved ->
            assertEquals(saved, PartyPlayerRule.preferenceFor(saved, partyActive = false))
        }
    }

    @Test
    fun `during a party every choice plays in the app`() {
        PlayerPreference.entries.forEach { saved ->
            assertEquals(PlayerPreference.INTERNAL, PartyPlayerRule.preferenceFor(saved, partyActive = true))
        }
    }
}
