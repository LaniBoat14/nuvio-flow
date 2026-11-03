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

/**
 * The server's own seek thumbnails for the file version behind one playback.
 *
 * Flow's media-server providers do not list trickplay or BIF files, so nothing creates one of these yet; the
 * engine accepts one when a provider supplies the three readers.
 */
class ServerThumbnailSource(
    val serverName: String,
    val format: ServerThumbnailFormat,
    val connectionId: String,
    val itemId: String,
    val mediaSourceId: String,
    private val readTrickplay: suspend () -> ServerTrickplaySet?,
    private val readSheet: suspend (listedAs: String, width: Int, index: Int, maxBytes: Long) -> ByteArray,
    private val readBif: suspend (width: Int, range: LongRange?, maxBytes: Long) -> ServerBytes
) {
    /** Same server, item and file version. */
    val key: String
        get() = "$connectionId:$itemId:$mediaSourceId"

    suspend fun trickplay(): ServerTrickplaySet? = readTrickplay()

    /** [listedAs]: the key the trickplay manifest used for this version. */
    suspend fun sheet(listedAs: String, width: Int, index: Int, maxBytes: Long): ByteArray =
        readSheet(listedAs, width, index, maxBytes)

    suspend fun bif(width: Int, range: LongRange?, maxBytes: Long): ServerBytes = readBif(width, range, maxBytes)

    override fun toString(): String = "ServerThumbnailSource($serverName, $format)"
}
