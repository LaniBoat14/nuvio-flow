package com.nuvio.tv.ui.support

import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.MemberTier
import com.nuvio.tv.domain.model.MembershipOverview
import com.nuvio.tv.domain.model.MembershipOverviewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportNuvioUiStateTest {
    private val account = AuthState.FullAccount(userId = "u1", email = "a@b.c")
    private val loaded = MembershipOverviewState(overview = MembershipOverview(), isLoading = false)

    @Test
    fun signedOutAsksToSignIn() {
        val state = supportNuvioUiState(AuthState.SignedOut, loaded, MemberAccess.None, suggestionsHidden = false)
        assertEquals(SupportStatus.SIGNED_OUT, state.status)
        assertTrue(state.showSupportButton)
    }

    @Test
    fun activeMembershipShowsTierAndManageLink() {
        val overview = MembershipOverview(
            status = "active",
            tier = MemberTier.SUPPORTER_PLUS,
            membershipLevel = MemberTier.SUPPORTER,
            supporterSince = "2026-03-14",
            subscriptionActive = true
        )
        val state = supportNuvioUiState(
            account,
            MembershipOverviewState(overview = overview, isLoading = false),
            MemberAccess.None,
            suggestionsHidden = true
        )
        assertEquals(SupportStatus.SUPPORTER, state.status)
        assertEquals(MemberTier.SUPPORTER_PLUS, state.tier)
        assertEquals("2026-03-14", state.supporterSince)
        assertTrue(state.manageOnPatreon)
        assertTrue(state.showSupportButton)
    }

    @Test
    fun oneTimePassShowsWhenItEnds() {
        val overview = MembershipOverview(
            status = "active",
            tier = MemberTier.SUPPORTER_PLUS,
            hasActiveGrant = true,
            grantTier = MemberTier.SUPPORTER_PLUS,
            grantExpiresAt = "2027-03-14T00:00:00Z"
        )
        val state = supportNuvioUiState(
            account,
            MembershipOverviewState(overview = overview, isLoading = false),
            MemberAccess.None,
            suggestionsHidden = false
        )
        assertEquals("2027-03-14T00:00:00Z", state.passEndsAt)
        assertFalse(state.manageOnPatreon)
    }

    @Test
    fun cachedAccessCountsAsSupporterWhileOverviewLoads() {
        val state = supportNuvioUiState(
            account,
            MembershipOverviewState(isLoading = true),
            MemberAccess(tier = MemberTier.SUPPORTER),
            suggestionsHidden = false
        )
        assertEquals(SupportStatus.SUPPORTER, state.status)
        assertEquals(MemberTier.SUPPORTER, state.tier)
    }

    @Test
    fun noMembershipIsGentleAndNoThanksHidesTheButton() {
        val shown = supportNuvioUiState(account, loaded, MemberAccess.None, suggestionsHidden = false)
        assertEquals(SupportStatus.NOT_SUPPORTER, shown.status)
        assertTrue(shown.showSupportButton)

        val hidden = supportNuvioUiState(account, loaded, MemberAccess.None, suggestionsHidden = true)
        assertFalse(hidden.showSupportButton)
    }

    @Test
    fun failedCheckWithoutEarlierAnswerIsUnavailable() {
        val state = supportNuvioUiState(
            account,
            MembershipOverviewState(overview = null, isLoading = false, hasError = true),
            MemberAccess.None,
            suggestionsHidden = false
        )
        assertEquals(SupportStatus.UNAVAILABLE, state.status)
    }

    @Test
    fun loadingAccountIsChecking() {
        val state = supportNuvioUiState(account, MembershipOverviewState(isLoading = true), MemberAccess.None, false)
        assertEquals(SupportStatus.CHECKING, state.status)
    }
}
