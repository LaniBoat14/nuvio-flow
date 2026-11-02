package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class FlowAdaptiveSourceTest {
    private val uri = mockk<Uri>(relaxed=true) { every { host } returns "test.invalid" }
    @Before fun clock() {
        mockkStatic(SystemClock::class);every { SystemClock.elapsedRealtime() } answers { System.nanoTime()/1_000_000 }
        mockkStatic(Log::class);every { Log.i(any(),any()) } returns 0
    }
    @After fun cleanup() {unmockkStatic(SystemClock::class);unmockkStatic(Log::class)}
    private class Server(val file: ByteArray, val ranges: Boolean, val uri: Uri) : DataSource.Factory {
        val active=AtomicInteger();val peak=AtomicInteger()
        override fun createDataSource(): DataSource = object:DataSource {
            var position=0;var end=0;var opened=false;var headers=emptyMap<String,List<String>>()
            override fun open(spec:DataSpec):Long {
                position=spec.position.toInt()
                end=if(spec.length<0)file.size else minOf(file.size,position+spec.length.toInt())
                if(!ranges)end=file.size
                headers=if(ranges)mapOf("Content-Range" to listOf("bytes $position-${end-1}/${file.size}")) else emptyMap()
                opened=true;val n=active.incrementAndGet();peak.updateAndGet{maxOf(it,n)}
                return (end-position).toLong()
            }
            override fun read(target:ByteArray,offset:Int,length:Int):Int {
                if(position>=end)return -1
                val n=minOf(length,end-position)
                file.copyInto(target,offset,position,position+n);position+=n;return n
            }
            override fun getResponseHeaders()=headers
            override fun getUri()=uri
            override fun close(){if(opened){opened=false;active.decrementAndGet()}}
            override fun addTransferListener(listener:TransferListener)=Unit
        }
    }
    private fun read(source:FlowAdaptiveSource, start:Int):ByteArray {
        source.open(DataSpec.Builder().setUri(uri).setPosition(start.toLong()).build())
        val out=ByteArrayOutputStream();val target=ByteArray(32*1024)
        while(true){val n=source.read(target,0,target.size);if(n<0)break;out.write(target,0,n)}
        source.close();return out.toByteArray()
    }
    @Test fun rangesProduceExactBytesAndRespectGlobalCap(){
        val file=ByteArray(20*1024*1024){(it%251).toByte()};val server=Server(file,true,uri)
        val source=FlowAdaptiveSource(mockk<Context>(relaxed=true),server,false,3,8)
        assertArrayEquals(file,read(source,0));assertTrue(server.peak.get()<=6)
    }
    @Test fun ignoredRangeFallsBackWithoutDuplicatingPrefix(){
        val file=ByteArray(200_000){(it%251).toByte()};val server=Server(file,false,uri)
        val source=FlowAdaptiveSource(mockk<Context>(relaxed=true),server,false)
        assertArrayEquals(file.copyOfRange(7000,file.size),read(source,7000))
    }
    @Test fun reopeningAtSeekPositionProducesExactSuffix(){
        val file=ByteArray(1_000_000){(it%251).toByte()};val server=Server(file,true,uri)
        val source=FlowAdaptiveSource(mockk<Context>(relaxed=true),server,false)
        source.open(DataSpec.Builder().setUri(uri).build());source.read(ByteArray(100),0,100);source.close()
        assertArrayEquals(file.copyOfRange(45678,file.size),read(source,45678))
    }
}
