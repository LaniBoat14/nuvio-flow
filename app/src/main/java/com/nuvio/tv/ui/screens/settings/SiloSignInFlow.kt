package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.network.ServerCertificateInfo
import com.nuvio.tv.core.network.ServerCertificateProbe
import com.nuvio.tv.core.network.ServerTrust
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.mediabrowser.normalizeServerAddress
import com.nuvio.tv.data.mediaserver.serverFailure
import com.nuvio.tv.data.mediaserver.silo.SiloCode
import com.nuvio.tv.data.mediaserver.silo.SiloCodeEnd
import com.nuvio.tv.data.mediaserver.silo.SiloCodeStatus
import com.nuvio.tv.data.mediaserver.silo.SiloNativeProvider
import com.nuvio.tv.data.mediaserver.silo.SiloProfile
import com.nuvio.tv.data.mediaserver.silo.SiloServer
import com.nuvio.tv.data.mediaserver.silo.SiloTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SiloSignInStep {
    FINDING,
    CODE,
    PASSWORD,
    SIGNING_IN,
    PROFILES,
    PIN,
    FAILED
}

enum class SiloCodePhase {
    STARTING,
    WAITING,
    EXPIRED,
    DENIED,
    UNAVAILABLE,
    FAILED
}

data class SiloSignInState(
    val step: SiloSignInStep = SiloSignInStep.FINDING,
    val server: SiloServer? = null,
    val code: SiloCode? = null,
    val codePhase: SiloCodePhase = SiloCodePhase.STARTING,
    val codeOpened: Boolean = false,
    val codeExpiresAtMs: Long = 0L,
    val busy: Boolean = false,
    val error: ServerFailure? = null,
    val issue: ServerSignInIssue? = null,
    val detail: String? = null,
    val profiles: List<SiloProfile> = emptyList(),
    val account: String? = null,
    val pinFor: SiloProfile? = null,
    val pinWrong: Boolean = false,
    val certificate: ServerCertificateInfo? = null
)

/** Signs in to Silo's own API: code (or Tailscale, or password), then a Silo profile. */
class SiloSignInFlow(
    private val repository: ServerRepository,
    private val scope: CoroutineScope,
    private val cleanup: CoroutineScope = scope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutableState = MutableStateFlow(SiloSignInState())
    val state: StateFlow<SiloSignInState> = mutableState.asStateFlow()

    private val provider: SiloNativeProvider?
        get() = repository.providers.filterIsInstance<SiloNativeProvider>().firstOrNull()

    private var job: Job? = null
    private var codeJob: Job? = null
    private var address = ""
    private var replacing: String? = null
    private var trusted: ServerCertificateInfo? = null
    private var tokens: SiloTokens? = null
    private var onConnected: (ServerConnection) -> Unit = {}

    fun start(address: String, replacing: String? = null, onConnected: (ServerConnection) -> Unit) {
        close()
        this.address = address
        this.replacing = replacing
        this.onConnected = onConnected
        find()
    }

    private fun find() {
        val provider = provider ?: return fail(ServerException(ServerFailure.UNSUPPORTED))
        mutableState.value = SiloSignInState(step = SiloSignInStep.FINDING)
        job = scope.launch {
            val server = try {
                provider.findServer(address)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val certificate = if (error.serverFailure() == ServerFailure.CERTIFICATE) {
                    normalizeServerAddress(address)?.let { ServerCertificateProbe.probe(it) }
                        ?.takeIf { it.fingerprint != trusted?.fingerprint }
                } else {
                    null
                }
                if (certificate != null) {
                    mutableState.value = SiloSignInState(step = SiloSignInStep.FAILED, certificate = certificate)
                } else {
                    fail(error)
                }
                return@launch
            }
            mutableState.value = SiloSignInState(step = SiloSignInStep.CODE, server = server)
            when {
                server.codeSignIn -> startCode(server)
                server.passwordSignIn && server.network == null -> usePassword()
                server.network == null -> mutableState.update { it.copy(codePhase = SiloCodePhase.UNAVAILABLE) }
            }
        }
    }

    fun trustCertificate() {
        val certificate = mutableState.value.certificate ?: return
        trusted = certificate
        ServerTrust.allow(certificate.host, certificate.fingerprint)
        find()
    }

    fun newCode() {
        val server = mutableState.value.server ?: return
        mutableState.update { it.copy(step = SiloSignInStep.CODE, error = null, issue = null, detail = null) }
        startCode(server)
    }

    fun useCode() {
        if (mutableState.value.server?.codeSignIn != true) return
        if (mutableState.value.code == null || mutableState.value.codePhase != SiloCodePhase.WAITING) {
            newCode()
        } else {
            mutableState.update { it.copy(step = SiloSignInStep.CODE, error = null, issue = null, detail = null) }
        }
    }

    fun usePassword() {
        if (mutableState.value.server?.passwordSignIn != true) return
        mutableState.update { it.copy(step = SiloSignInStep.PASSWORD, busy = false, error = null, issue = null, detail = null) }
    }

    private fun startCode(server: SiloServer) {
        val provider = provider ?: return
        val previous = codeJob
        val previousCode = mutableState.value.code
        previous?.cancel()
        mutableState.update { it.copy(code = null, codePhase = SiloCodePhase.STARTING, codeOpened = false) }
        codeJob = scope.launch {
            previous?.join()
            previousCode?.let { provider.cancelCode(server, it) }
            runCode(provider, server)
        }
    }

    private suspend fun runCode(provider: SiloNativeProvider, server: SiloServer) {
        val startedAt = clock()
        while (true) {
            val code = try {
                provider.startCode(server)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val phase = if (error.serverFailure() == ServerFailure.NOT_FOUND) SiloCodePhase.UNAVAILABLE else SiloCodePhase.FAILED
                mutableState.update { it.copy(codePhase = phase, error = error.serverFailure()) }
                return
            }
            mutableState.update {
                it.copy(code = code, codePhase = SiloCodePhase.WAITING, codeOpened = false, codeExpiresAtMs = code.expiresAtMs)
            }
            when (val end = awaitCode(provider, server, code)) {
                null -> return
                SiloCodeEnd.EXPIRED -> {
                    val renew = !mutableState.value.codeOpened && clock() - startedAt < CODE_RENEW_FOR_MS
                    if (!renew) {
                        mutableState.update { it.copy(codePhase = SiloCodePhase.EXPIRED) }
                        return
                    }
                    provider.cancelCode(server, code)
                }
                SiloCodeEnd.DENIED, SiloCodeEnd.CANCELED -> {
                    mutableState.update { it.copy(codePhase = SiloCodePhase.DENIED) }
                    return
                }
                SiloCodeEnd.USED -> {
                    mutableState.update { it.copy(codePhase = SiloCodePhase.EXPIRED) }
                    return
                }
            }
        }
    }

    /** Null once approved (and handed on), otherwise why the code ended. */
    private suspend fun awaitCode(provider: SiloNativeProvider, server: SiloServer, code: SiloCode): SiloCodeEnd? {
        var waitMs = 0L
        var backoffMs = code.pollEveryMs
        var deadline = code.expiresAtMs
        while (true) {
            if (waitMs > 0L) delay(waitMs.coerceAtMost((deadline - clock()).coerceAtLeast(POLL_MIN_MS)))
            if (clock() >= deadline) return SiloCodeEnd.EXPIRED
            val status = try {
                provider.pollCode(server, code)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                waitMs = backoffMs
                backoffMs = (backoffMs * 2).coerceAtMost(POLL_BACKOFF_MAX_MS)
                continue
            }
            backoffMs = code.pollEveryMs
            when (status) {
                is SiloCodeStatus.Waiting -> {
                    deadline = maxOf(deadline, status.expiresAtMs)
                    mutableState.update { it.copy(codeOpened = it.codeOpened || status.opened, codeExpiresAtMs = deadline) }
                    waitMs = status.pollAfterMs
                }
                is SiloCodeStatus.Approved -> {
                    signedIn(server, status.tokens)
                    return null
                }
                is SiloCodeStatus.Ended -> return status.reason
            }
        }
    }

    fun signInWithPassword(username: String, password: String) {
        val provider = provider ?: return
        val server = mutableState.value.server ?: return
        if (mutableState.value.busy || username.isBlank() || password.isEmpty()) return
        mutableState.update { it.copy(busy = true, error = null, issue = null, detail = null) }
        job = scope.launch {
            val signedIn = try {
                provider.passwordSignIn(server, username, password)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(busy = false).withError(error) }
                return@launch
            }
            signedIn(server, signedIn)
        }
    }

    fun continueWithNetwork() {
        val provider = provider ?: return
        val server = mutableState.value.server ?: return
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, issue = null, detail = null) }
        job = scope.launch {
            val signedIn = try {
                provider.networkSignIn(server)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(busy = false).withError(error) }
                return@launch
            }
            signedIn(server, signedIn)
        }
    }

    private suspend fun signedIn(server: SiloServer, signedIn: SiloTokens) {
        val provider = provider ?: return
        val polling = codeJob
        if (polling != null && polling != currentCoroutineContext()[Job]) {
            polling.cancel()
            val code = mutableState.value.code
            if (code != null && mutableState.value.codePhase == SiloCodePhase.WAITING) {
                scope.launch { provider.cancelCode(server, code) }
            }
        }
        tokens = signedIn
        mutableState.update { it.copy(step = SiloSignInStep.SIGNING_IN, busy = true, error = null, issue = null, detail = null) }
        val profiles = try {
            provider.checkUsable(server, signedIn)
            provider.profiles(server, signedIn)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            abandon()
            fail(error)
            return
        }
        val only = profiles.singleOrNull()
        when {
            profiles.isEmpty() -> {
                abandon()
                fail(ServerException(ServerFailure.FAILED))
            }
            only != null && !only.hasPin -> finish(only, null)
            only != null -> mutableState.update {
                it.copy(step = SiloSignInStep.PIN, busy = false, profiles = profiles, pinFor = only, pinWrong = false)
            }
            else -> mutableState.update {
                it.copy(step = SiloSignInStep.PROFILES, busy = false, profiles = profiles, account = signedIn.userName)
            }
        }
    }

    fun pickProfile(profile: SiloProfile) {
        if (mutableState.value.busy) return
        if (profile.hasPin) {
            mutableState.update { it.copy(step = SiloSignInStep.PIN, pinFor = profile, pinWrong = false, error = null) }
        } else {
            job = scope.launch { finish(profile, null) }
        }
    }

    fun backToProfiles() {
        if (mutableState.value.busy || mutableState.value.profiles.size < 2) return
        mutableState.update { it.copy(step = SiloSignInStep.PROFILES, pinFor = null, pinWrong = false, error = null) }
    }

    fun submitPin(pin: String) {
        val provider = provider ?: return
        val state = mutableState.value
        val server = state.server ?: return
        val profile = state.pinFor ?: return
        val signedIn = tokens ?: return
        if (state.busy || pin.isBlank()) return
        mutableState.update { it.copy(busy = true, pinWrong = false, error = null) }
        job = scope.launch {
            val profileToken = try {
                provider.verifyPin(server, signedIn, profile, pin.trim())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(busy = false).withError(error) }
                return@launch
            }
            if (profileToken == null) {
                mutableState.update { it.copy(busy = false, pinWrong = true) }
            } else {
                finish(profile, profileToken)
            }
        }
    }

    private suspend fun finish(profile: SiloProfile, profileToken: String?) {
        val provider = provider ?: return
        val server = mutableState.value.server ?: return
        val signedIn = tokens ?: return
        mutableState.update { it.copy(step = SiloSignInStep.SIGNING_IN, busy = true, error = null) }
        val accepted = trusted?.let { it.host to it.fingerprint }
        val connection = try {
            repository.connectWith(provider, accepted, replacing) { provider.signIn(server, signedIn, profile, profileToken) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            abandon()
            fail(error)
            return
        }
        tokens = null
        mutableState.value = SiloSignInState()
        onConnected(connection)
    }

    /** Leaves the flow: an unused code is withdrawn and a sign-in that wasn't saved is ended on the server. */
    fun close() {
        job?.cancel()
        codeJob?.cancel()
        job = null
        codeJob = null
        val state = mutableState.value
        val server = state.server
        val code = state.code
        val provider = provider
        if (server != null && provider != null && code != null && state.codePhase == SiloCodePhase.WAITING) {
            cleanup.launch { provider.cancelCode(server, code) }
        }
        abandon()
        if (trusted != null) ServerTrust.clearPending()
        trusted = null
        mutableState.value = SiloSignInState()
    }

    private fun abandon() {
        val signedIn = tokens ?: return
        val server = mutableState.value.server ?: return
        val provider = provider ?: return
        tokens = null
        cleanup.launch { withContext(NonCancellable) { provider.abandon(server, signedIn) } }
    }

    private fun fail(error: Exception) {
        mutableState.update { it.copy(step = SiloSignInStep.FAILED, busy = false).withError(error) }
    }

    private fun SiloSignInState.withError(error: Exception): SiloSignInState {
        val server = error as? ServerException
        return copy(error = error.serverFailure(), issue = server?.issue, detail = server?.detail)
    }

    private companion object {
        const val POLL_MIN_MS = 250L
        const val POLL_BACKOFF_MAX_MS = 30_000L
        const val CODE_RENEW_FOR_MS = 60 * 60_000L
    }
}
