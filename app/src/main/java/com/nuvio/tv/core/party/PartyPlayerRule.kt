package com.nuvio.tv.core.party

import com.nuvio.tv.data.local.PlayerPreference

/** While a watch party is on, playback stays in the app's own player so the party can follow it. */
object PartyPlayerRule {
    fun preferenceFor(saved: PlayerPreference, partyActive: Boolean): PlayerPreference =
        if (partyActive) PlayerPreference.INTERNAL else saved
}
