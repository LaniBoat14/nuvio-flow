package com.nuvio.tv.ui.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.data.local.SupportNuvioDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.data.repository.MembershipOverviewRepository
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.MemberTier
import com.nuvio.tv.domain.model.MembershipOverviewState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SupportStatus {
    SIGNED_OUT,
    CHECKING,
    NOT_SUPPORTER,
    UNAVAILABLE,
    SUPPORTER
}

data class SupportNuvioUiState(
    val status: SupportStatus = SupportStatus.CHECKING,
    val tier: MemberTier? = null,
    val supporterSince: String? = null,
    val manageOnPatreon: Boolean = false,
    val passEndsAt: String? = null,
    val isRefreshing: Boolean = false,
    val suggestionsHidden: Boolean = false
) {
    val showSupportButton: Boolean
        get() = AppFeaturePolicy.supportNuvioEnabled && (status == SupportStatus.SUPPORTER || !suggestionsHidden)
}

internal fun supportNuvioUiState(
    authState: AuthState,
    membership: MembershipOverviewState,
    access: MemberAccess,
    suggestionsHidden: Boolean
): SupportNuvioUiState {
    val base = SupportNuvioUiState(suggestionsHidden = suggestionsHidden)
    return when (authState) {
        AuthState.SignedOut -> base.copy(status = SupportStatus.SIGNED_OUT)
        AuthState.Loading -> base.copy(status = SupportStatus.CHECKING)
        is AuthState.FullAccount -> {
            val overview = membership.overview?.takeIf { it.active }
            val tier = overview?.let { it.tier ?: it.membershipLevel ?: it.grantTier } ?: access.tier
            val status = when {
                tier != null -> SupportStatus.SUPPORTER
                membership.isLoading -> SupportStatus.CHECKING
                membership.hasError && membership.overview == null -> SupportStatus.UNAVAILABLE
                else -> SupportStatus.NOT_SUPPORTER
            }
            base.copy(
                status = status,
                tier = tier,
                supporterSince = overview?.supporterSince,
                manageOnPatreon = membership.overview?.subscriptionActive == true,
                passEndsAt = overview
                    ?.takeIf { it.hasActiveGrant && !it.grantIsLifetime && !it.hasLifetimeGrant && !it.subscriptionActive }
                    ?.grantExpiresAt,
                isRefreshing = membership.isRefreshing
            )
        }
    }
}

@HiltViewModel
class SupportNuvioViewModel @Inject constructor(
    authManager: AuthManager,
    private val membershipOverviewRepository: MembershipOverviewRepository,
    private val memberAccessRepository: MemberAccessRepository,
    private val supportNuvioDataStore: SupportNuvioDataStore
) : ViewModel() {

    val uiState: StateFlow<SupportNuvioUiState> = combine(
        authManager.authState,
        membershipOverviewRepository.state,
        memberAccessRepository.access,
        supportNuvioDataStore.suggestionsHidden,
        ::supportNuvioUiState
    ).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        supportNuvioUiState(
            authManager.authState.value,
            membershipOverviewRepository.state.value,
            memberAccessRepository.access.value,
            suggestionsHidden = true
        )
    )

    fun refreshStatus() {
        membershipOverviewRepository.refresh()
    }

    fun checkAgain() {
        membershipOverviewRepository.refresh()
        memberAccessRepository.refresh()
    }

    fun toggleSuggestionsHidden() {
        viewModelScope.launch { supportNuvioDataStore.toggleSuggestionsHidden() }
    }

    fun setSuggestionsHidden(hidden: Boolean) {
        viewModelScope.launch { supportNuvioDataStore.setSuggestionsHidden(hidden) }
    }
}
