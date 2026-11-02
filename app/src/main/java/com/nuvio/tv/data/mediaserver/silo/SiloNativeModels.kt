package com.nuvio.tv.data.mediaserver.silo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A Silo server found at an address, with the ways it lets a TV sign in. */
class SiloServer(
    val address: String,
    val serverId: String,
    val name: String,
    val passwordSignIn: Boolean,
    val codeSignIn: Boolean,
    internal val cancelsCodes: Boolean,
    val network: SiloNetworkSignIn?
) {
    override fun toString(): String = "SiloServer(serverId=$serverId)"
}

/** Silo offers this only to a device that reaches it through Tailscale; [name] is who the device belongs to. */
class SiloNetworkSignIn(
    val name: String,
    internal val path: String
)

class SiloCode(
    internal val deviceCode: String,
    val userCode: String,
    val link: String,
    val linkWithCode: String,
    val expiresAtMs: Long,
    internal val serverExpiresAt: String?,
    val pollEveryMs: Long
) {
    override fun toString(): String = "SiloCode(expiresAtMs=$expiresAtMs)"

    val shownCode: String
        get() = userCode.filter(Char::isDigit).let { digits ->
            if (digits.length == 8) "${digits.take(4)} ${digits.drop(4)}" else userCode
        }
}

sealed interface SiloCodeStatus {
    /** [opened] once someone looked the code up; [expiresAtMs] moves later while they do. */
    data class Waiting(val opened: Boolean, val expiresAtMs: Long, val pollAfterMs: Long) : SiloCodeStatus

    class Approved(val tokens: SiloTokens) : SiloCodeStatus {
        override fun toString(): String = "Approved"
    }

    data class Ended(val reason: SiloCodeEnd) : SiloCodeStatus
}

enum class SiloCodeEnd {
    EXPIRED,
    DENIED,
    CANCELED,
    USED
}

class SiloTokens(
    internal val access: String,
    internal val refresh: String,
    internal val accessExpiresAtMs: Long,
    val userId: String,
    val userName: String,
    val passwordChangeNeeded: Boolean
) {
    override fun toString(): String = "SiloTokens(userId=$userId)"
}

data class SiloProfile(
    val id: String,
    val name: String,
    val hasPin: Boolean,
    val isChild: Boolean,
    val isPrimary: Boolean
)

/** What a Silo sign-in keeps for a connection, stored as the connection's token. */
@Serializable
internal data class SiloCredential(
    @SerialName("access") val access: String,
    @SerialName("refresh") val refresh: String,
    @SerialName("expires_at_ms") val accessExpiresAtMs: Long,
    @SerialName("profile_id") val profileId: String,
    @SerialName("profile_name") val profileName: String = "",
    @SerialName("profile_token") val profileToken: String? = null
) {
    override fun toString(): String = "SiloCredential(profileId=$profileId)"
}

@Serializable
internal data class SystemInfoWire(@SerialName("api_major") val apiMajor: Int? = null)

@Serializable
internal data class IdentityWire(@SerialName("server_id") val serverId: String = "")

@Serializable
internal data class JellyfinInfoWire(@SerialName("Id") val id: String? = null)

@Serializable
internal data class BrandingWire(@SerialName("server_name") val serverName: String? = null)

@Serializable
internal data class ProvidersWire(
    val items: List<ProviderWire> = emptyList(),
    @SerialName("password_login") val passwordLogin: Boolean = false
)

@Serializable
internal data class ProviderWire(
    val id: String = "",
    val mode: String = "",
    @SerialName("network_sign_in_path") val networkSignInPath: String? = null,
    @SerialName("network_identity") val networkIdentity: NetworkIdentityWire? = null
)

@Serializable
internal data class NetworkIdentityWire(
    @SerialName("display_name") val displayName: String = "",
    val username: String = ""
)

@Serializable
internal data class DeviceCapabilityWire(
    val state: String = "",
    val cancel: Boolean = false
)

@Serializable
internal data class DeviceStartBody(
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_platform") val devicePlatform: String = "android-tv"
)

@Serializable
internal data class DeviceStartWire(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("verification_uri_complete") val verificationUriComplete: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 900,
    val interval: Long = 3
)

@Serializable
internal data class DeviceCodeBody(@SerialName("device_code") val deviceCode: String)

@Serializable
internal data class DevicePollWire(
    val status: String = "",
    @SerialName("poll_after") val pollAfter: Long? = null,
    val opened: Boolean = false,
    @SerialName("expires_at") val expiresAt: String? = null,
    val tokens: TokenPairWire? = null
)

@Serializable
internal data class LoginBody(
    val username: String,
    val password: String
)

@Serializable
internal class EmptyBody

@Serializable
internal data class TokenPairWire(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val user: AccountWire? = null
)

@Serializable
internal data class AccountWire(
    val id: String = "",
    val username: String = "",
    @SerialName("password_change_required") val passwordChangeRequired: Boolean = false
)

@Serializable
internal data class RefreshBody(@SerialName("refresh_token") val refreshToken: String)

@Serializable
internal data class ProfilesWire(val items: List<ProfileWire> = emptyList())

@Serializable
internal data class ProfileWire(
    val id: String = "",
    val name: String = "",
    @SerialName("has_pin") val hasPin: Boolean = false,
    @SerialName("is_child") val isChild: Boolean = false,
    @SerialName("is_primary") val isPrimary: Boolean = false
)

@Serializable
internal data class PinBody(val pin: String)

@Serializable
internal data class PinWire(
    val valid: Boolean = false,
    @SerialName("profile_token") val profileToken: String? = null
)

@Serializable
internal data class UserLibrariesWire(val items: List<UserLibraryWire> = emptyList())

@Serializable
internal data class UserLibraryWire(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    @SerialName("sort_order") val sortOrder: Long = 0
)
