package com.nuvio.tv.core.player.thumbnail

internal class VideoPool(val freeMb: Int, val totalMb: Int) {
    companion object {
        /** No decoder is added below this much free (4K, up to 1080p); one is taken away below [DROP_BELOW_MB]. */
        const val ADD_MARGIN_4K_MB = 200
        const val ADD_MARGIN_HD_MB = 80
        const val DROP_BELOW_MB = 48

        /** Above 1080p: what one more decoder is expected to take (more if more was seen), and the free pool below which one stays. */
        const val COST_4K_MB = 165
        const val DROP_4K_TO_ONE_MB = 150

        /** Above 1080p, a box with less RAM than this (or RAM not known) keeps one hardware decoder. */
        const val SMALL_BOX_TOTAL_MB = 3_072L

        /** A smaller CMA area is not where the decoders live (MediaTek shows 16 MB, always empty). */
        const val MIN_POOL_KB = 64L * 1024

        fun parse(meminfo: String): VideoPool? {
            var free = -1L
            var total = -1L
            for (line in meminfo.lineSequence()) {
                val kb = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull() ?: continue
                when {
                    line.startsWith("CmaFree:") -> free = kb
                    line.startsWith("CmaTotal:") -> total = kb
                }
            }
            if (free < 0 || total < MIN_POOL_KB) return null
            return VideoPool((free / 1024).toInt(), (total / 1024).toInt())
        }

        fun addMarginMb(fourK: Boolean): Int = if (fourK) ADD_MARGIN_4K_MB else ADD_MARGIN_HD_MB

        fun mayAdd(freeMb: Int?, fourK: Boolean): Boolean = freeMb == null || freeMb >= addMarginMb(fourK)

        /** Above 1080p: the pool must be readable and keep [ADD_MARGIN_4K_MB] after one more decoder's [costMb]. */
        fun mayAdd4K(freeMb: Int?, costMb: Int): Boolean = freeMb != null && freeMb - costMb >= ADD_MARGIN_4K_MB

        fun oneOnly4K(totalRamMb: Long, poolReadable: Boolean): Boolean =
            totalRamMb < SMALL_BOX_TOTAL_MB || !poolReadable

        fun mustDrop(freeMb: Int?): Boolean = freeMb != null && freeMb < DROP_BELOW_MB
    }
}

internal object VideoPoolReader {
    fun read(): VideoPool? = runCatching { VideoPool.parse(java.io.File("/proc/meminfo").readText()) }.getOrNull()
}
