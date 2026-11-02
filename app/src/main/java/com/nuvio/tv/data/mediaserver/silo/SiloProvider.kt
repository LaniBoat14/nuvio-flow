package com.nuvio.tv.data.mediaserver.silo

import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerSignIn
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.mediabrowser.normalizeServerAddress
import okhttp3.OkHttpClient

internal class SiloProvider(
    http: OkHttpClient,
    identity: ServerClientIdentity
) : JellyfinProvider(http, identity) {
    override val id: String = ID
    override val displayName: String = "Silo"
    override val supportsQuickConnect: Boolean
        get() = false

    private val website = SiloWebsite(client)

    override suspend fun signIn(address: String, username: String, password: String): ServerSignIn = try {
        super.signIn(address, username, password)
    } catch (error: ServerException) {
        if (error.issue != ServerSignInIssue.WEBSITE) throw error
        val siteAddress = normalizeServerAddress(address) ?: throw error
        val jellyfin = website.jellyfinSignIn(siteAddress, username, password)
        try {
            super.signIn(jellyfin.address, jellyfin.username, password)
        } catch (failed: ServerException) {
            val unreachable = failed.network || failed.failure == ServerFailure.NOT_FOUND ||
                failed.issue == ServerSignInIssue.WEBSITE
            if (unreachable) throw ServerException(ServerFailure.FAILED, issue = ServerSignInIssue.SILO_WEBSITE)
            if (failed.failure == ServerFailure.CERTIFICATE) {
                throw ServerException(ServerFailure.CERTIFICATE, failed.message, address = jellyfin.address)
            }
            throw failed
        }
    }

    override fun segmentItemId(itemId: String, mediaSourceId: String?): String = mediaSourceId ?: itemId

    companion object {
        const val ID = "silo"
    }
}
