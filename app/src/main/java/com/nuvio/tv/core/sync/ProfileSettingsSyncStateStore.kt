package com.nuvio.tv.core.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFERENCES_NAME = "profile_settings_sync_state"
private const val LAST_USER_ID_KEY = "lastUserId"

private val profileSettingsPushRetryDelaysMs = longArrayOf(5_000L, 30_000L, 120_000L)

internal data class PendingProfileSettingsPush(
    val ownerUserId: String?,
    val sequence: Long,
    val lastLocalEditMs: Long
)

/** A pending local edit wins over the cloud copy only for the account it was made under. */
internal fun shouldPushPendingInsteadOfApplyingRemote(
    pending: PendingProfileSettingsPush?,
    currentUserId: String?
): Boolean = pending != null && currentUserId != null && pending.ownerUserId == currentUserId

/** An edit made while a push was in flight keeps the marker until its own push succeeds. */
internal fun canClearPendingAfterPush(
    pending: PendingProfileSettingsPush?,
    pushedSequence: Long
): Boolean = pending == null || pending.sequence <= pushedSequence

internal fun profileSettingsPushRetryDelayMs(attempt: Int): Long? =
    profileSettingsPushRetryDelaysMs.getOrNull(attempt)

@Singleton
class ProfileSettingsSyncStateStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val lock = Any()

    internal fun pending(profileId: Int): PendingProfileSettingsPush? = synchronized(lock) {
        if (!preferences.getBoolean(pendingKey(profileId), false)) return null
        PendingProfileSettingsPush(
            ownerUserId = preferences.getString(ownerKey(profileId), null)?.takeIf { it.isNotEmpty() },
            sequence = preferences.getLong(sequenceKey(profileId), 0L),
            lastLocalEditMs = preferences.getLong(lastEditKey(profileId), 0L)
        )
    }

    internal fun currentSequence(profileId: Int): Long = synchronized(lock) {
        preferences.getLong(sequenceKey(profileId), 0L)
    }

    internal fun markPending(profileId: Int, ownerUserId: String?, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val sequence = preferences.getLong(sequenceKey(profileId), 0L) + 1L
            preferences.edit()
                .putBoolean(pendingKey(profileId), true)
                .putString(ownerKey(profileId), ownerUserId.orEmpty())
                .putLong(sequenceKey(profileId), sequence)
                .putLong(lastEditKey(profileId), nowMs)
                .commit()
        }
    }

    internal fun clearPending(profileId: Int, pushedSequence: Long? = null): Boolean = synchronized(lock) {
        val current = pending(profileId) ?: return true
        if (pushedSequence != null && !canClearPendingAfterPush(current, pushedSequence)) return false
        preferences.edit()
            .remove(pendingKey(profileId))
            .remove(ownerKey(profileId))
            .commit()
        true
    }

    internal var lastUserId: String?
        get() = synchronized(lock) { preferences.getString(LAST_USER_ID_KEY, null)?.takeIf { it.isNotEmpty() } }
        set(value) {
            synchronized(lock) {
                val stored = preferences.getString(LAST_USER_ID_KEY, null)?.takeIf { it.isNotEmpty() }
                val wanted = value?.takeIf { it.isNotEmpty() }
                if (stored == wanted) return
                if (wanted == null) {
                    preferences.edit().remove(LAST_USER_ID_KEY).apply()
                } else {
                    preferences.edit().putString(LAST_USER_ID_KEY, wanted).apply()
                }
            }
        }

    private fun pendingKey(profileId: Int) = "pendingPush_$profileId"
    private fun ownerKey(profileId: Int) = "pendingOwner_$profileId"
    private fun sequenceKey(profileId: Int) = "pendingSeq_$profileId"
    private fun lastEditKey(profileId: Int) = "lastLocalEditMs_$profileId"
}
