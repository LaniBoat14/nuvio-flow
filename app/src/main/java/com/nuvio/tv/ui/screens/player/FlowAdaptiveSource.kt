package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.nuvio.tv.core.network.adaptive.AdaptivePolicy
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object FlowAdaptiveRuntime {
    @Volatile var bufferSeconds = 0.0
    @Volatile var durationMs = 0L
    @Volatile var reservedMb = 0
    @Volatile var mode = "Single"
    @Volatile var reason = "Starting"
}

/** Range workers share a transport factory and an app-wide six-request budget. */
@UnstableApi
internal class FlowAdaptiveSource(
    private val context: Context,
    private val upstream: DataSource.Factory,
    private val adaptive: Boolean = true,
    private val fixedWorkers: Int = 3,
    private val fixedChunkMb: Int = 16
) : DataSource {
    companion object {
        private val workers = Executors.newFixedThreadPool(6) { r -> Thread(r,"FlowRange").apply { isDaemon=true } }
        private val permits = Semaphore(6, true)
        private val hedgePermit = Semaphore(1)
        private val cleanup = Executors.newSingleThreadScheduledExecutor {r->Thread(r,"FlowRangeCleanup").apply{isDaemon=true}}
        private const val MB = 1024 * 1024
    }
    private class Chunk(val start: Long, val size: Int) {
        val result = CompletableFuture<ByteArray>()
        val sources = java.util.concurrent.CopyOnWriteArrayList<DataSource>()
        val count = AtomicLong()
        val credited = AtomicLong()
        val cancelled = AtomicBoolean()
        val started = SystemClock.elapsedRealtime()
        var hedged = false
        val running = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var failure: Exception? = null
        var offset = 0
        fun cancel() { cancelled.set(true); sources.forEach { runCatching { it.close() } }; result.cancel(false) }
    }
    private val closed = AtomicBoolean()
    private val readerClosed = AtomicBoolean()
    private val epoch = AtomicLong()
    private val chunks = java.util.concurrent.ConcurrentSkipListMap<Long,Chunk>()
    private val listeners = mutableListOf<TransferListener>()
    private var spec: DataSpec? = null
    private var fallback: DataSource? = null
    private var uri: Uri? = null
    private var position = 0L
    private var end = 0L
    private var fileLength = 0L
    private var validator: String? = null
    private var next = 0L
    private var policy = AdaptivePolicy()
    private var lastTick = 0L
    private var previousBytes = 0L
    private val delivered = AtomicLong()
    private val speeds = ArrayDeque<Long>()
    private val modeScores = mutableMapOf<Int,ArrayDeque<Long>>()
    private val preferences by lazy { context.getSharedPreferences("flow-adaptive-hosts",Context.MODE_PRIVATE) }

    @Synchronized override fun open(dataSpec: DataSpec): Long {
        epoch.incrementAndGet();readerClosed.set(false)
        if(!closed.get() && fallback==null && fileLength>0 && dataSpec.uri==spec?.uri && dataSpec.httpRequestHeaders==spec?.httpRequestHeaders && dataSpec.position<fileLength) {
            position=dataSpec.position;next=position;spec=dataSpec
            end=if(dataSpec.length<0)fileLength else minOf(fileLength,position+dataSpec.length)
            val windowEnd=minOf(end,position+mode().workers*mode().chunkMb.toLong()*MB)
            chunks.values.filter{it.result.isCompletedExceptionally || it.result.isCancelled || it.start+it.size<=position || it.start>=windowEnd || it.start+it.size>windowEnd}.forEach{chunks.remove(it.start);it.cancel()}
            chunks.floorEntry(position)?.value?.takeIf{position<it.start+it.size}?.let{it.offset=(position-it.start).toInt()}
            schedule();return end-position
        }
        hardClose(); closed.set(false); spec=dataSpec; position=dataSpec.position; next=position
        delivered.set(0);previousBytes=0;lastTick=SystemClock.elapsedRealtime();modeScores.clear();synchronized(speeds){speeds.clear()}
        val probe=upstream.createDataSource()
        try {
            probe.open(dataSpec.buildUpon().setLength(1).build())
            uri=probe.uri ?: dataSpec.uri
            val range=probe.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range",true) }?.value?.firstOrNull()
            val total=range?.substringAfterLast('/')?.toLongOrNull()
            if(total==null || total<=position) {
                probe.close()
                val single=upstream.createDataSource();listeners.forEach(single::addTransferListener)
                fallback=single;FlowAdaptiveRuntime.mode="Single (range fallback)"
                return single.open(dataSpec)
            }
            fileLength=total
            validator=probe.responseHeaders.entries.firstOrNull { it.key.equals("ETag",true) }?.value?.firstOrNull()?.takeUnless{it.startsWith("W/")}
                ?: probe.responseHeaders.entries.firstOrNull { it.key.equals("Last-Modified",true) }?.value?.firstOrNull()
            end=if(dataSpec.length==C.LENGTH_UNSET.toLong()) total else minOf(total, position+dataSpec.length)
            val key=uri?.host ?: ""
            val recent=System.currentTimeMillis()-preferences.getLong("$key.updated",0)<24*60*60*1000L
            policy=AdaptivePolicy(if(adaptive && recent) preferences.getInt(key,3) else 3, lastTick)
        } finally { runCatching { probe.close() } }
        if(budgetMb()<16) {
            val single=upstream.createDataSource();listeners.forEach(single::addTransferListener)
            fallback=single;FlowAdaptiveRuntime.mode="Single (memory fallback)"
            return single.open(dataSpec)
        }
        schedule()
        return end-position
    }
    private var memoryAt = Long.MIN_VALUE
    private var cachedBudget = 16
    private var memoryPressure = false
    private fun budgetMb(): Int {
        val now=SystemClock.elapsedRealtime()
        if(memoryAt!=Long.MIN_VALUE && now-memoryAt<2000)return cachedBudget
        memoryAt=now
        val rt=Runtime.getRuntime()
        val heapRoom=(rt.maxMemory()-(rt.totalMemory()-rt.freeMemory())-32L*MB).coerceAtLeast(0)
        var systemRoom=Long.MAX_VALUE
        val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if(manager!=null) {
            val info=android.app.ActivityManager.MemoryInfo();manager.getMemoryInfo(info)
            systemRoom=(info.availMem-info.threshold-64L*MB).coerceAtLeast(0)
            memoryPressure=info.lowMemory || systemRoom<64L*MB
        }
        cachedBudget=minOf(96L,heapRoom/MB/2,systemRoom/MB/2).toInt()
        return cachedBudget
    }
    private fun mode(): AdaptivePolicy.Mode = if(adaptive) policy.mode else AdaptivePolicy.Mode(fixedWorkers.coerceIn(1,4),fixedChunkMb.coerceIn(8,16))
    private fun schedule() {
        next=maxOf(next,position)
        val m=mode()
        val capacity=(budgetMb()/m.chunkMb).coerceIn(1,m.workers)
        FlowAdaptiveRuntime.reservedMb=(capacity+1)*m.chunkMb
        FlowAdaptiveRuntime.mode="${capacity}×${m.chunkMb} MB${if(adaptive) " · Auto" else " · Fixed"}"
        val covering=chunks.floorEntry(position)?.value?.takeIf{position<it.start+it.size}
        if(covering==null && chunks.size>=capacity) chunks.pollLastEntry()?.value?.cancel()
        while(!closed.get() && chunks.size<capacity && next<end) {
            val retained=chunks.floorEntry(next)?.value?.takeIf{next<it.start+it.size}
            if(retained!=null){next=retained.start+retained.size;continue}
            val boundary=minOf(end,chunks.ceilingKey(next) ?: end)
            val size=minOf(m.chunkMb.toLong()*MB,boundary-next).toInt()
            val c=Chunk(next,size);chunks[next]=c;next+=size;launch(c,false)
        }
    }
    private fun launch(chunk: Chunk, hedge: Boolean) {
        if(hedge && !hedgePermit.tryAcquire()) return
        chunk.running.incrementAndGet()
        workers.execute {
            var acquired=false
            try {
                permits.acquire();acquired=true
                if(closed.get() || chunk.cancelled.get() || chunk.result.isDone) return@execute
                var last: Exception?=null
                for(attempt in 0..2) {
                    if(closed.get() || chunk.cancelled.get() || chunk.result.isDone) break
                    val source=upstream.createDataSource();chunk.sources.add(source)
                    listeners.forEach(source::addTransferListener)
                    val start=SystemClock.elapsedRealtime()
                    try {
                        val headers=spec!!.httpRequestHeaders.toMutableMap()
                        validator?.let { headers["If-Range"]=it }
                        source.open(spec!!.buildUpon().setPosition(chunk.start).setLength(chunk.size.toLong()).setHttpRequestHeaders(headers).build())
                        val contentRange=source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range",true) }?.value?.firstOrNull()
                        val expected="bytes ${chunk.start}-${chunk.start+chunk.size-1}/$fileLength"
                        if(contentRange!=expected) throw IOException("Range response changed")
                        val rt=Runtime.getRuntime()
                        if(rt.maxMemory()-(rt.totalMemory()-rt.freeMemory()) < chunk.size*2L+16L*MB) throw IOException("Insufficient range memory")
                        val bytes=ByteArray(chunk.size)
                        var offset=0
                        while(offset<bytes.size && !closed.get() && !chunk.cancelled.get() && !chunk.result.isDone) {
                            val n=source.read(bytes,offset,minOf(256*1024,bytes.size-offset))
                            if(n<0) throw IOException("Short range")
                            if(n>0) {
                                offset+=n
                                var old=chunk.credited.get()
                                while(offset>old) {
                                    if(chunk.credited.compareAndSet(old,offset.toLong())) {delivered.addAndGet(offset-old);break}
                                    old=chunk.credited.get()
                                }
                                if(!hedge)chunk.count.set(offset.toLong())
                            }
                        }
                        if(offset==bytes.size && !closed.get() && !chunk.cancelled.get()) {
                            val elapsed=(SystemClock.elapsedRealtime()-start).coerceAtLeast(1)
                            synchronized(speeds) { speeds.addLast(bytes.size*8000L/elapsed);while(speeds.size>12)speeds.removeFirst() }
                            if(chunk.result.complete(bytes)) chunk.sources.filter { it!==source }.forEach { runCatching { it.close() } }
                            android.util.Log.i("FlowRange","range=${chunk.start}-${chunk.start+chunk.size-1} bytes=$offset ms=$elapsed hedge=$hedge attempt=$attempt outcome=complete host=${source.uri?.host}")
                            return@execute
                        }
                    } catch(e:Exception) {last=e} finally { chunk.sources.remove(source);runCatching { source.close() } }
                    if(!chunk.result.isDone && !closed.get()) Thread.sleep(250L*(1L shl attempt))
                }
                chunk.failure=last ?: IOException("Range cancelled")
            } catch(e:OutOfMemoryError) {
                memoryPressure=true;cachedBudget=8;memoryAt=SystemClock.elapsedRealtime()
                chunk.failure=IOException("Range allocation failed; reduce memory",e)
            } catch(e:Exception) {chunk.failure=e} finally {
                if(acquired)permits.release()
                if(hedge)hedgePermit.release()
                if(chunk.running.decrementAndGet()==0 && !chunk.result.isDone) chunk.result.completeExceptionally(chunk.failure ?: IOException("Range cancelled"))
            }
        }
    }
    @Synchronized override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        fallback?.let{return it.read(buffer,offset,length)}
        if(length==0)return 0
        if(closed.get() || readerClosed.get())throw IOException("Source closed")
        if(position>=end)return C.RESULT_END_OF_INPUT
        schedule()
        val c=chunks.firstEntry()?.value ?: throw IOException("No scheduled range")
        while(!c.result.isDone) {
            if(closed.get() || readerClosed.get())throw IOException("Source closed")
            tick()
            val elapsed=SystemClock.elapsedRealtime()-c.started
            val median=synchronized(speeds){speeds.sorted().let{if(it.size>=3)it[it.size/2] else null}}
            if(!c.hedged && chunks.size>=3 && budgetMb()>=(chunks.size+1)*mode().chunkMb && median!=null && elapsed>2000 &&
                (c.count.get()*8000/elapsed<median/2 || elapsed>c.size*16000L/median)) {
                c.hedged=true;launch(c,true)
            }
            try {c.result.get(100,TimeUnit.MILLISECONDS)}catch(_:java.util.concurrent.TimeoutException){}catch(e:Exception){throw IOException("Range failed",e)}
        }
        val bytes=try{c.result.get()}catch(e:Exception){throw IOException("Range failed",e)}
        val count=minOf(length,bytes.size-c.offset)
        bytes.copyInto(buffer,offset,c.offset,c.offset+count);c.offset+=count;position+=count
        if(c.offset==bytes.size){chunks.pollFirstEntry();schedule()}
        tick()
        return count
    }
    private fun tick() {
        val now=SystemClock.elapsedRealtime();if(now-lastTick<2000)return
        val bytes=delivered.get();val rate=(bytes-previousBytes)*8000/(now-lastTick)
        if(adaptive) {
            val bitrate=FlowAdaptiveRuntime.durationMs.takeIf{it>0}?.let{fileLength*8000/it}
            val scores=modeScores.getOrPut(policy.index){ArrayDeque()}
            if(rate>0) {scores.addLast(rate);while(scores.size>8)scores.removeFirst()}
            val d=policy.evaluate(now,bitrate,FlowAdaptiveRuntime.bufferSeconds,rate.takeIf{it>0},budgetMb(),memoryPressure)
            if(d.reprobe) {
                val best=modeScores.filterValues{it.size>=3}.maxByOrNull{(_,v)->v.sorted()[v.size/2]}?.key
                if(best!=null && best!=policy.index) policy.selectObservedMode(best,now)
            }
            FlowAdaptiveRuntime.reason=d.reason
            // Save observed profiles only after sufficient active transfer history.
            if(synchronized(speeds){speeds.size>=6} && d.reason=="Stable" && FlowAdaptiveRuntime.bufferSeconds>=30) {
                val key=uri?.host ?: ""
                preferences.edit().putInt(key,policy.index).putLong("$key.updated",System.currentTimeMillis()).apply()
            }
        }
        lastTick=now;previousBytes=bytes
    }
    override fun close() {
        readerClosed.set(true)
        val token=epoch.get()
        cleanup.schedule({synchronized(this){if(epoch.get()==token && readerClosed.get())hardClose()}},750,TimeUnit.MILLISECONDS)
    }
    private fun hardClose() {
        closed.set(true);FlowAdaptiveRuntime.reservedMb=0;chunks.values.forEach{it.cancel()};chunks.clear()
        fallback?.let{runCatching{it.close()}};fallback=null
    }
    override fun getUri(): Uri? = fallback?.uri ?: uri
    override fun getResponseHeaders(): Map<String,List<String>> = fallback?.responseHeaders ?: emptyMap()
    override fun addTransferListener(transferListener: TransferListener) {if(!listeners.contains(transferListener))listeners.add(transferListener)}
}


/** Fresh reader leases share only this film's bounded range session across seeks. */
@UnstableApi
internal class FlowAdaptiveFactory(private val session: FlowAdaptiveSource):DataSource.Factory {
    private val generation=AtomicLong()
    override fun createDataSource():DataSource = object:DataSource {
        private var token=-1L
        override fun open(spec:DataSpec):Long {token=generation.incrementAndGet();return session.open(spec)}
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            if(token!=generation.get())throw IOException("Reader superseded by seek")
            return session.read(buffer,offset,length)
        }
        override fun close(){if(token==generation.get())session.close()}
        override fun getUri()=session.uri
        override fun getResponseHeaders()=session.responseHeaders
        override fun addTransferListener(listener:TransferListener)=session.addTransferListener(listener)
    }
}
