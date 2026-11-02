package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tracking.TrackingListManagementCapabilities
import com.nuvio.tv.core.tracking.TrackingListManager
import com.nuvio.tv.core.tracking.supportsMembershipFor
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListPrivacy
import com.nuvio.tv.domain.model.ListMembershipChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class MdbListLibraryWriter(
    private val api: MdbListApiClient,
    private val sync: MdbListSyncRepository,
    private val now: () -> Long
) : TrackingListManager {
    override val capabilities = TrackingListManagementCapabilities(
        privacyOptions = listOf(LibraryListPrivacy.PRIVATE, LibraryListPrivacy.PUBLIC)
    )

    override suspend fun createList(name: String, description: String?, privacy: LibraryListPrivacy) {
        val body = metadataBody(name, description, privacy)
        val scope = sync.currentScope()
        write(scope) { library ->
            val response = mdbListResponseElement(api.post("/lists/user/add", body, scope).body).objectValue()
            val id = response.number("id")?.takeIf { it > 0 } ?: throw MdbListDecodingException()
            val created = MdbListLibraryList(id, response.text("name") ?: name.trim(), privacy == LibraryListPrivacy.PRIVATE)
            library.copy(lists = library.lists.filterNot { it.id == id } + created,
                itemsByList = library.itemsByList + (created.key to emptyList()))
        }
    }

    override suspend fun updateList(key: String, name: String, description: String?, privacy: LibraryListPrivacy) {
        val body = metadataBody(name, description, privacy)
        val id = mdbListPersonalListId(key)
        val scope = sync.currentScope()
        write(scope) { library ->
            require(library.lists.any { it.id == id }) { "This static list is no longer available" }
            val response = mdbListResponseElement(api.put("/lists/$id", body, scope).body).objectValue()
            if (response.flag("success") != true || response.number("id") != id) throw MdbListDecodingException()
            library.copy(lists = library.lists.map { list ->
                if (list.id != id) list else list.copy(name = response.text("name") ?: name.trim(),
                    private = response.flag("private") ?: (privacy == LibraryListPrivacy.PRIVATE))
            })
        }
    }

    override suspend fun deleteList(key: String) {
        val id = mdbListPersonalListId(key)
        val scope = sync.currentScope()
        write(scope) { library ->
            require(library.lists.any { it.id == id }) { "This static list is no longer available" }
            val response = api.delete("/lists/$id", scope)
            if (response.status != 204) {
                val body = mdbListResponseElement(response.body).objectValue()
                if (body.flag("success") != true || body.number("id") != id) throw MdbListDecodingException()
            }
            library.copy(lists = library.lists.filterNot { it.id == id }, itemsByList = library.itemsByList - key,
                addedOrders = library.addedOrders - key)
        }
    }

    private val membershipMutex = Mutex()
    private val pending = MutableStateFlow<List<MdbListMembershipWrite>>(emptyList())

    /** Membership changes MDBList accepted that are not in the cached library yet. */
    val pendingWrites: StateFlow<List<MdbListMembershipWrite>> = pending.asStateFlow()

    fun pendingWrites(scope: MdbListAuthScope?): List<MdbListMembershipWrite> = pending.value.filter { it.scope == scope }

    suspend fun currentLibrary(): MdbListLibrarySnapshot? {
        if (sync.currentSnapshot() == null) sync.ensureLoaded()
        val scope = runCatching { sync.currentScope() }.getOrNull() ?: return null
        return sync.currentSnapshot()?.library?.withWrites(pendingWrites(scope))
    }

    /**
     * Sends the changes against the cached library without taking the sync lock, so a running library
     * or history sync does not hold the write back. With [commitScope] the cache is updated there
     * afterwards and [afterCommit] runs; until then [pendingWrites] carries the changes.
     */
    suspend fun applyMembershipChanges(
        scope: MdbListAuthScope,
        input: LibraryEntryInput,
        changes: ListMembershipChanges,
        commitScope: CoroutineScope? = null,
        afterCommit: suspend () -> Unit = {}
    ) {
        val suppliedTarget = runCatching { input.mdbListLibraryItem() }.getOrNull()
        val keys = changes.desiredMembership.keys.filterNot(::isMdbListReadOnlyListKey)
        if (keys.isEmpty()) return
        membershipMutex.withLock {
            var written = false
            var failed = true
            try {
                var library = libraryFor(scope, keys)
                for (key in keys) {
                    val desired = changes.desiredMembership.getValue(key)
                    mdbListLibraryItemsPath(key)
                    val current = library?.let { cached ->
                        val tab = cached.tabs().firstOrNull { it.key == key }
                        require(tab != null) { "This list is no longer available" }
                        if (!desired && !tab.supportsMembershipFor(input.itemType)) {
                            null
                        } else {
                            require(tab.supportsMembershipFor(input.itemType)) { "This list does not accept this item" }
                            cached.itemsByList[key].orEmpty()
                        }
                    }
                    if (library != null && current == null) continue
                    val resolved = suppliedTarget ?: current?.firstOrNull {
                        it.type == mdbListLibraryType(input.itemType) && input.itemId in it.media.ids.aliases()
                    }
                    if (resolved == null && !desired) continue
                    val target = requireNotNull(resolved) { "An external movie or show ID is required" }
                    if (current != null && current.any(target::matches) == desired) continue
                    val action = if (desired) "add" else "remove"
                    val response = api.post("${mdbListLibraryItemsPath(key)}/$action", target.membershipBody(key == MDBLIST_WATCHLIST_KEY), scope)
                    verifyMdbListMembershipResponse(response.body, target.type, desired)
                    val write = MdbListMembershipWrite(scope, key, target, desired, now())
                    pending.update { it + write }
                    library = library?.withWrite(write)
                    written = true
                }
                failed = false
            } finally {
                if (written || failed) {
                    val invalidate = failed
                    if (commitScope != null) {
                        commitScope.launch {
                            commitPending(scope, invalidate)
                            afterCommit()
                        }
                    } else {
                        withContext(NonCancellable) { commitPending(scope, invalidate) }
                    }
                }
            }
        }
    }

    /** Moves [pendingWrites] into the cached library; they stay pending while there is no cached library. */
    suspend fun commitPending(scope: MdbListAuthScope, invalidate: Boolean = false) {
        val applied = try {
            sync.mutate(scope) { previous ->
                val library = previous.library ?: return@mutate previous to emptyList()
                val writes = pendingWrites(scope)
                val updated = library.withWrites(writes).let { if (invalidate) it.copy(invalidated = true) else it }
                previous.copy(library = updated) to writes
            }
        } catch (_: CancellationException) {
            pending.update { writes -> writes.filter { it.scope != scope } }
            return
        } catch (_: Exception) {
            return
        }
        val currentScope = runCatching { sync.currentScope() }.getOrNull()
        pending.update { writes -> writes.filterNot { write -> applied.any { it === write } || write.scope != currentScope } }
    }

    private suspend fun libraryFor(scope: MdbListAuthScope, keys: Collection<String>): MdbListLibrarySnapshot? {
        if (sync.currentSnapshot() == null) sync.ensureLoaded()
        sync.currentSnapshot()?.library?.let { return it.withWrites(pendingWrites(scope)) }
        // Without a cached library a watchlist change needs no list details.
        if (keys.all { it == MDBLIST_WATCHLIST_KEY }) return null
        return sync.mutate(scope) { previous ->
            val library = previous.library ?: MdbListLibraryRemote(api, scope).synchronize(null, previous.accountId, now())
            previous.copy(library = library) to library
        }.withWrites(pendingWrites(scope))
    }

    private fun metadataBody(name: String, description: String?, privacy: LibraryListPrivacy): String {
        require(name.isNotBlank()) { "Enter a list name" }
        require(description.isNullOrBlank()) { "MDBList does not support editing list descriptions" }
        require(privacy in capabilities.privacyOptions) { "MDBList supports private and public lists" }
        return buildJsonObject { put("name", name.trim()); put("private", privacy == LibraryListPrivacy.PRIVATE) }.toString()
    }

    private suspend fun write(scope: MdbListAuthScope, update: suspend (MdbListLibrarySnapshot) -> MdbListLibrarySnapshot) {
        try {
            sync.mutate(scope) { previous ->
                val library = previous.library ?: MdbListLibraryRemote(api, scope).synchronize(null, previous.accountId, now())
                previous.copy(library = update(library)) to Unit
            }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                try {
                    sync.mutate(scope) { previous -> previous.copy(library = previous.library?.copy(invalidated = true)) to Unit }
                } catch (_: Exception) {
                }
            }
            throw error
        }
    }
}

internal data class MdbListMembershipWrite(
    val scope: MdbListAuthScope,
    val key: String,
    val target: MdbListLibraryItem,
    val desired: Boolean,
    val writtenAt: Long
)

internal fun MdbListLibrarySnapshot.withWrites(writes: List<MdbListMembershipWrite>): MdbListLibrarySnapshot =
    writes.fold(this) { library, write -> library.withWrite(write) }

internal fun MdbListLibrarySnapshot.withWrite(write: MdbListMembershipWrite): MdbListLibrarySnapshot {
    val current = itemsByList[write.key] ?: if (write.key == MDBLIST_WATCHLIST_KEY) emptyList() else return this
    if (current.any(write.target::matches) == write.desired) return this
    val items = if (write.desired) {
        current + write.target.copy(listedAt = write.writtenAt, rank = (current.mapNotNull { it.rank }.maxOrNull() ?: 0) + 1)
    } else {
        current.filterNot(write.target::matches)
    }
    return copy(itemsByList = itemsByList + (write.key to items), addedOrders = addedOrders - write.key)
}
