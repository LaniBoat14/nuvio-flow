package com.nuvio.tv.core.player.thumbnail

import java.io.File

// Arc memory reader used by automatic networking; Flow has no thumbnail allocator.
internal object MemoryGovernor {
    internal class Meminfo(val availableMb: Long, val swapTotalMb: Long, val swapFreeMb: Long)

    internal fun parseMeminfo(text: String): Meminfo? {
        var avail = -1L
        var swapTotal = 0L
        var swapFree = 0L
        for (line in text.lineSequence()) {
            val kb = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull() ?: continue
            when {
                line.startsWith("MemAvailable:") -> avail = kb / 1024
                line.startsWith("SwapTotal:") -> swapTotal = kb / 1024
                line.startsWith("SwapFree:") -> swapFree = kb / 1024
            }
        }
        return if (avail >= 0) Meminfo(avail, swapTotal, swapFree) else null
    }

    internal fun readMeminfo(): Meminfo? = runCatching { parseMeminfo(File("/proc/meminfo").readText()) }.getOrNull()

}
