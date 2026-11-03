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
    private fun context(): Context = mockk<Context>(relaxed=true) {
        every { getSystemService(Context.ACTIVITY_SERVICE) } returns null
    }
    private val uri = mockk<Uri>(relaxed=true) { every { host } returns "test.invalid" }
    @Before fun clock() {
        mockkStatic(SystemClock::class);every { SystemClock.elapsedRealtime() } answers { System.nanoTime()/1_000_000 }
        mockkStatic(Log::class);every { Log.i(any(),any()) } returns 0
    }
    @After fun cleanup() {unmockkStatic(SystemClock::class);unmockkStatic(Log::class)}
    private class Server(val file: ByteArray, val ranges: Boolean, val uri: Uri) : DataSource.Factory {
        val active=AtomicInteger();val peak=AtomicInteger()
        val opens=java.util.concurrent.CopyOnWriteArrayList<Long>()
        override fun createDataSource(): DataSource = object:DataSource {
            var position=0;var end=0;var opened=false;var headers=emptyMap<String,List<String>>()
            override fun open(spec:DataSpec):Long {
                opens.add(spec.length)
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
            override fun getUri(): Uri = this@Server.uri
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
        val source=FlowAdaptiveSource(context(),server,false,3,8)
        assertArrayEquals(file,read(source,0));assertTrue(server.peak.get()<=6)
    }
    @Test fun ignoredRangeFallsBackWithoutDuplicatingPrefix(){
        val file=ByteArray(200_000){(it%251).toByte()};val server=Server(file,false,uri)
        val source=FlowAdaptiveSource(context(),server,false)
        assertArrayEquals(file.copyOfRange(7000,file.size),read(source,7000))
    }
    @Test fun reopeningAtSeekPositionProducesExactSuffix(){
        val file=ByteArray(1_000_000){(it%251).toByte()};val server=Server(file,true,uri)
        val source=FlowAdaptiveSource(context(),server,false)
        source.open(DataSpec.Builder().setUri(uri).build());source.read(ByteArray(100),0,100);source.close()
        assertArrayEquals(file.copyOfRange(45678,file.size),read(source,45678))
    }
    @Test fun supersededReaderCannotCloseTheNewSeekLease(){
        val file=ByteArray(1_000_000){(it%251).toByte()};val server=Server(file,true,uri)
        val factory=FlowAdaptiveFactory(FlowAdaptiveSource(context(),server,false))
        val first=factory.createDataSource();first.open(DataSpec.Builder().setUri(uri).build())
        first.read(ByteArray(100),0,100);first.close()
        val second=factory.createDataSource();second.open(DataSpec.Builder().setUri(uri).setPosition(1000).build())
        first.close()
        val bytes=ByteArray(100);assertEquals(100,second.read(bytes,0,100))
        assertArrayEquals(file.copyOfRange(1000,1100),bytes);second.close()
    }

    @Test fun observedConnectionPreservesRangeHeadersAndBytes(){
        val file=ByteArray(1_000_000){(it%251).toByte()};val server=Server(file,true,uri)
        val observed=DataSource.Factory { FlowObservedConnection(server.createDataSource(),FlowConnectionInfo()) }
        val source=FlowAdaptiveSource(context(),observed,false)
        assertArrayEquals(file,read(source,0))
        assertTrue("Capability probe followed by bounded range",server.opens.size>=2)
        assertTrue("No unbounded fallback request",server.opens.none { it < 0 })
    }

    @Test fun partialRangeFailureRetriesWithoutDuplicatingBytes(){
        val file=ByteArray(1_000_000){(it%251).toByte()};val server=Server(file,true,uri)
        val failed=java.util.concurrent.atomic.AtomicBoolean()
        val recoveryCount=AtomicInteger()
        val primary=DataSource.Factory {
            val original=server.createDataSource()
            object:DataSource by original {
                override fun getResponseHeaders() = original.responseHeaders
                var readOnce=false
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    if(readOnce && failed.compareAndSet(false,true))throw java.io.IOException("Injected range failure")
                    readOnce=true
                    return original.read(buffer,offset,minOf(length,100_000))
                }
            }
        }
        val recovery=DataSource.Factory {recoveryCount.incrementAndGet();server.createDataSource()}
        val source=FlowAdaptiveSource(context(),primary,false,3,16,recovery)
        assertArrayEquals(file,read(source,0))
        assertTrue(recoveryCount.get()>0)
        assertTrue("Must exercise ranged fetching, not raw fallback", server.opens.none { it < 0 })
    }

}
