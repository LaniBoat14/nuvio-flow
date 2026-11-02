package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbGiveUpContextTest {
    @Test fun aServerComesFirstThenUsenetThenTorrentThenDebrid() {
        assertEquals("media server (Emby)", thumbSourceKind("Emby", usenet = true, torrent = false, debridStore = "TorBox"))
        assertEquals("Usenet", thumbSourceKind(null, usenet = true, torrent = false, debridStore = "TorBox"))
        assertEquals("torrent", thumbSourceKind(null, usenet = false, torrent = true, debridStore = null))
        assertEquals("debrid direct (TorBox)", thumbSourceKind(null, usenet = false, torrent = false, debridStore = "TorBox"))
        assertEquals("addon HTTP", thumbSourceKind(null, usenet = false, torrent = false, debridStore = null))
    }

    @Test fun theStoreIsReadFromTheLabelOnly() {
        assertEquals("TorBox", debridStoreOf("[TB \u26a1] Some Addon 2160p", null))
        assertEquals("Real-Debrid", debridStoreOf("Addon", "Cached on Real-Debrid"))
        assertEquals(null, debridStoreOf("WEB-DL HDR 2160p", "HEVC"))
    }

    @Test fun theSettingsNoteNamesEngineBuffersAndBox() {
        assertEquals(
            "engine ExoPlayer, native memory on, managed budget on, target buffer auto, custom buffers off, " +
                "box 3072 MB lowRam no",
            thumbSettingsNote(InternalPlayerEngine.EXOPLAYER, PlayerSettings(bufferSettings = BufferSettings(targetBufferSizeMb = 0)),
                nativeMemory = true, totalRamMb = 3072, lowRam = false),
        )
        assertEquals(
            "engine MPV, native memory off, managed budget off, target buffer 300 MB, custom buffers on, " +
                "box 1912 MB lowRam yes",
            thumbSettingsNote(
                InternalPlayerEngine.MVP_PLAYER,
                PlayerSettings(bufferEngineEnabled = true, bufferBudgetManaged = false,
                    bufferSettings = BufferSettings(targetBufferSizeMb = 300)),
                nativeMemory = false, totalRamMb = 1912, lowRam = true,
            ),
        )
    }
}
