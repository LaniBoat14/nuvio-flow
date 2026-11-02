package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.BufferMemoryKeeper
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings

/** Where the stream comes from, for the thumbnails "gave up" line. Only fixed words and store or server type names. */
internal fun thumbSourceKind(serverType: String?, usenet: Boolean, torrent: Boolean, debridStore: String?): String = when {
    serverType != null -> "media server ($serverType)"
    usenet -> "Usenet"
    torrent -> "torrent"
    debridStore != null -> "debrid direct ($debridStore)"
    else -> "addon HTTP"
}

/** Player engine, memory and buffer settings and the box class, for the thumbnails "gave up" line. */
internal fun thumbSettingsNote(
    engine: InternalPlayerEngine,
    settings: PlayerSettings,
    nativeMemory: Boolean,
    totalRamMb: Long,
    lowRam: Boolean,
): String {
    val engineName = when (engine) {
        InternalPlayerEngine.EXOPLAYER -> "ExoPlayer"
        InternalPlayerEngine.MVP_PLAYER -> "MPV"
        InternalPlayerEngine.AUTO -> "Auto"
    }
    fun onOff(b: Boolean) = if (b) "on" else "off"
    val managed = BufferMemoryKeeper.budgetManaged(settings.bufferEngineEnabled, settings.bufferBudgetManaged)
    return "engine $engineName, native memory ${onOff(nativeMemory)}, managed budget ${onOff(managed)}, " +
        "target buffer ${if (managed) "auto" else "${settings.bufferSettings.targetBufferSizeMb} MB"}, custom buffers ${onOff(settings.bufferEngineEnabled)}, " +
        "box ${totalRamMb} MB lowRam ${if (lowRam) "yes" else "no"}"
}
