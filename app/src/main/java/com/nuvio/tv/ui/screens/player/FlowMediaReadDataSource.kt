package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.util.concurrent.atomic.AtomicLong

/** Only bytes delivered to the progressive media extractor; excludes subtitle/metadata requests. */
internal object FlowMediaReadMeter {
    val epoch = AtomicLong()
    val bytes = AtomicLong()
}

@UnstableApi
internal class FlowMediaReadDataSource(private val upstream: DataSource) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        FlowMediaReadMeter.bytes.set(0)
        FlowMediaReadMeter.epoch.incrementAndGet()
        return length
    }
    override fun read(buffer: java.nio.ByteBuffer, length: Int): Int {
        val count = upstream.read(buffer, length)
        if (count > 0) FlowMediaReadMeter.bytes.addAndGet(count.toLong())
        return count
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = upstream.read(buffer, offset, length)
        if (count > 0) FlowMediaReadMeter.bytes.addAndGet(count.toLong())
        return count
    }
}
