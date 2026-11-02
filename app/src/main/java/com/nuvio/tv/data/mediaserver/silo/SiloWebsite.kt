package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.mediabrowser.MediaBrowserClient
import com.nuvio.tv.data.mediaserver.mediabrowser.dropsEncryption
import com.nuvio.tv.data.mediaserver.mediabrowser.normalizeServerAddress
import com.nuvio.tv.data.mediaserver.mediabrowser.withSignInIssue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Silo serves its website and its Jellyfin-compatible API on different addresses. When someone types the website,
 * a short sign-in there reads the Jellyfin-compatible address the server owner set up.
 */
internal class SiloWebsite(private val client: MediaBrowserClient) {

    suspend fun jellyfinSignIn(typed: String, username: String, password: String): SiloJellyfinSignIn {
        val website = siloSite(typed) ?: throw ServerException(ServerFailure.FAILED, issue = ServerSignInIssue.WEBSITE)
        val name = username.trim()
        val (token, account) = login(website, name, password)
        var jellyfinName = name
        val info = try {
            if (account == name && '#' in name) jellyfinName = withProfile(website, token, name)
            client.json.decodeFromString(
                ConnectInfoReply.serializer(),
                client.execute("GET", website, CONNECT_INFO_PATH, token = null, authorization = "Bearer $token").body
            )
        } catch (error: ServerException) {
            throw error.withSignInIssue()
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.FAILED, issue = ServerSignInIssue.SILO_WEBSITE)
        } finally {
            logout(website, token)
        }
        if (!info.jellyfin.enabled) throw ServerException(ServerFailure.FAILED, issue = ServerSignInIssue.SILO_JELLYFIN_OFF)
        val published = normalizeServerAddress(info.jellyfin.publicUrl.orEmpty())
        val address = jellyfinCandidate(website, info.jellyfin.publicUrl)
            ?.takeIf { it == published || isSiloJellyfin(it) }
            ?: throw ServerException(ServerFailure.FAILED, issue = ServerSignInIssue.SILO_WEBSITE)
        return SiloJellyfinSignIn(address, jellyfinName)
    }

    /** A guessed address can be another server on the same machine (often a real Jellyfin on 8096). */
    private suspend fun isSiloJellyfin(address: String): Boolean = try {
        client.json.decodeFromString(
            BrandingReply.serializer(),
            client.execute("GET", address, BRANDING_PATH, token = null).body
        ).loginDisclaimer?.contains("Silo", ignoreCase = true) == true
    } catch (_: ServerException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /** The website's address once it answers as Silo, following its move from http to https if it makes one. */
    private suspend fun siloSite(typed: String): String? {
        var site = typed
        repeat(2) {
            val reply = try {
                client.execute("GET", site, SYSTEM_INFO_PATH, token = null, allowRedirect = true, authorization = null)
            } catch (_: ServerException) {
                return null
            }
            if (reply.status !in 300..399) return site.takeIf { isSiloInfo(reply.body) }
            site = reply.location
                ?.substringBefore(SYSTEM_INFO_PATH)
                ?.let { normalizeServerAddress(it) }
                ?.takeIf { it != site && it.toHttpUrlOrNull()?.host == site.toHttpUrlOrNull()?.host && !dropsEncryption(site, it) }
                ?: return null
        }
        return null
    }

    private fun isSiloInfo(body: String): Boolean = try {
        !client.json.decodeFromString(SystemInfoReply.serializer(), body).serverVersion.isNullOrBlank()
    } catch (_: IllegalArgumentException) {
        false
    }

    /** At most two tries, so a typo never adds up to Silo's sign-in limit or a directory lockout. */
    private suspend fun login(website: String, name: String, password: String): Pair<String, String> {
        var refused: ServerException? = null
        for ((account, secret) in loginTries(name, password)) {
            try {
                return loginOnce(website, account, secret) to account
            } catch (error: ServerException) {
                if (error.failure != ServerFailure.AUTH_REQUIRED || error.issue != null) throw error
                refused = error
            }
        }
        throw refused ?: ServerException(ServerFailure.AUTH_REQUIRED)
    }

    private suspend fun loginOnce(website: String, account: String, password: String): String {
        val body = client.json.encodeToString(LoginRequest.serializer(), LoginRequest(account, password))
        val reply = try {
            client.execute("POST", website, LOGIN_PATH, token = null, body = body, authorization = null)
        } catch (error: ServerException) {
            throw error.withSignInIssue()
        }
        return try {
            client.json.decodeFromString(TokenReply.serializer(), reply.body).accessToken
        } catch (_: IllegalArgumentException) {
            null
        }?.takeIf { it.isNotBlank() } ?: throw ServerException(ServerFailure.FAILED, issue = ServerSignInIssue.SILO_WEBSITE)
    }

    /**
     * Silo's Jellyfin sign-in reads everything after the last # as a profile, so an account whose own name has a #
     * needs one added. The choice follows Silo's own rule for a name without a profile; anything less clear-cut is
     * left to the person, who then gets the message asking for the profile.
     */
    private suspend fun withProfile(website: String, token: String, account: String): String {
        val profiles = try {
            client.json.decodeFromString(
                ProfilesReply.serializer(),
                client.execute("GET", website, PROFILES_PATH, token = null, authorization = "Bearer $token").body
            ).items
        } catch (_: ServerException) {
            return account
        } catch (_: IllegalArgumentException) {
            return account
        }
        val profile = profiles.filter { it.name.equals(account, ignoreCase = true) }.singleOrNull()
            ?: profiles.filter { !it.hasPin }.singleOrNull()
            ?: return account
        return if ('#' in profile.name) account else "$account#${profile.name}"
    }

    private suspend fun logout(website: String, token: String) {
        withContext(NonCancellable) {
            try {
                client.execute("POST", website, LOGOUT_PATH, token = null, authorization = "Bearer $token")
            } catch (_: Exception) {
            }
        }
    }

    internal companion object {
        const val SYSTEM_INFO_PATH = "/api/v2/system/info"
        const val LOGIN_PATH = "/api/v2/auth/login"
        const val LOGOUT_PATH = "/api/v2/auth/logout"
        const val CONNECT_INFO_PATH = "/api/v2/compat/connect-info"
        const val PROFILES_PATH = "/api/v2/profiles"
        const val BRANDING_PATH = "/Branding/Configuration"
        const val JELLYFIN_PORT = 8096
        private val PIN_SUFFIX = Regex("#\\d{1,12}$")

        /** The Jellyfin-compatible sign-in takes "account#profile"; the website takes the account alone. */
        fun accountName(username: String): String {
            val account = username.substringBeforeLast('#', missingDelimiterValue = username).trim()
            return account.ifEmpty { username.trim() }
        }

        /**
         * The usual "name#Profile" goes first and a name whose own spelling has a # (old Discord names) second.
         * A password ending in # and digits is the Jellyfin "password#PIN" form, so it is tried without the PIN first.
         */
        fun loginTries(name: String, password: String): List<Pair<String, String>> {
            val withoutPin = password.takeIf { PIN_SUFFIX.containsMatchIn(it) }
                ?.substringBeforeLast('#')
                ?.takeIf { it.isNotEmpty() }
            val passwords = listOfNotNull(withoutPin, password)
            val accounts = listOf(accountName(name), name).distinct()
            return if (accounts.size > 1) accounts.map { it to passwords.first() } else passwords.map { accounts.first() to it }
        }

        /**
         * The owner's published address when there is one. Silo's default points at the server itself, so then the
         * default port on the website's host is tried. Neither may turn an https website into plain http.
         */
        fun jellyfinCandidate(website: String, publicUrl: String?): String? {
            normalizeServerAddress(publicUrl.orEmpty())
                ?.takeIf { address -> address.toHttpUrlOrNull()?.host?.let(::isLoopback) == false }
                ?.let { return it.takeUnless { dropsEncryption(website, it) } }
            val site = website.toHttpUrlOrNull() ?: return null
            if (site.isHttps) return null
            return site.newBuilder().port(JELLYFIN_PORT).encodedPath("/").build().toString().trimEnd('/')
        }

        private fun isLoopback(host: String): Boolean {
            val name = host.lowercase().removePrefix("[").removeSuffix("]")
            return name == "localhost" || name == "::1" || name == "0.0.0.0" ||
                name.endsWith(".localhost") || name.startsWith("127.")
        }
    }
}

internal data class SiloJellyfinSignIn(val address: String, val username: String)

@Serializable
private data class ProfilesReply(val items: List<WebsiteProfile> = emptyList())

@Serializable
private data class WebsiteProfile(
    val name: String = "",
    @SerialName("has_pin") val hasPin: Boolean = false
)

@Serializable
private data class BrandingReply(@SerialName("LoginDisclaimer") val loginDisclaimer: String? = null)

@Serializable
private data class SystemInfoReply(@SerialName("server_version") val serverVersion: String? = null)

@Serializable
private data class LoginRequest(val username: String, val password: String)

@Serializable
private data class TokenReply(@SerialName("access_token") val accessToken: String? = null)

@Serializable
private data class ConnectInfoReply(val jellyfin: JellyfinConnectInfo = JellyfinConnectInfo())

@Serializable
private data class JellyfinConnectInfo(
    val enabled: Boolean = false,
    @SerialName("public_url") val publicUrl: String? = null
)
