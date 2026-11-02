package com.nuvio.tv.data.mediaserver

enum class ServerThumbnailFormat {
    /** Jellyfin and Silo: sprite sheets listed per media source under the item's Trickplay field. */
    TRICKPLAY,

    /** Emby: one BIF file per video. */
    BIF
}

/** One width of a server's trickplay set: sheets of [tileWidth] x [tileHeight] pictures, one picture per [intervalMs]. */
data class ServerTrickplay(
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val thumbnailCount: Int,
    val intervalMs: Long
)

/** The widths made for one file version; [mediaSourceId] is the key the server listed them under. */
data class ServerTrickplaySet(
    val mediaSourceId: String,
    val levels: List<ServerTrickplay>
)

/** A binary reply. [partial]: the server answered a range request with 206. [truncated]: cut at the size limit. */
class ServerBytes(
    val bytes: ByteArray,
    val partial: Boolean,
    val totalLength: Long?,
    val truncated: Boolean
)

/** The server's own seek thumbnails for the file version behind one playback, fetched with that playback's sign-in. */
class ServerThumbnailSource internal constructor(
    val serverName: String,
    val format: ServerThumbnailFormat,
    val connectionId: String,
    val itemId: String,
    val mediaSourceId: String,
    private val provider: ServerProvider,
    private val session: () -> ServerSession
) {
    /** Same server, item and file version. */
    val key: String
        get() = "$connectionId:$itemId:$mediaSourceId"

    suspend fun trickplay(): ServerTrickplaySet? = provider.trickplay(session(), itemId, mediaSourceId)

    /** [listedAs]: the key the trickplay manifest used for this version. */
    suspend fun sheet(listedAs: String, width: Int, index: Int, maxBytes: Long): ByteArray =
        provider.trickplaySheet(session(), itemId, listedAs, width, index, maxBytes)

    suspend fun bif(width: Int, range: LongRange?, maxBytes: Long): ServerBytes =
        provider.bif(session(), itemId, mediaSourceId, width, range, maxBytes)

    override fun toString(): String = "ServerThumbnailSource($serverName, $format)"
}
