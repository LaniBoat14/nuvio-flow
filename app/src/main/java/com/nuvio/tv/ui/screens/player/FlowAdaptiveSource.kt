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
    @Volatile var playbackSpeed = 1.0
    @Volatile var protocol: String? = null
    @Volatile var peer: String? = null
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
    private val fixedChunkMb: Int = 16,
    private val recoveryFactory: DataSource.Factory = upstream,
    private val alternateFactory: DataSource.Factory = upstream
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
    @Volatile private var fallback: DataSource? = null
    @Volatile private var openingSource: DataSource? = null
    private var uri: Uri? = null
    private var position = 0L
    private var end = 0L
    private var fileLength = 0L
    private var validator: String? = null
    private var next = 0L
    private var policy = AdaptivePolicy()
    private var lastTick = 0L
    private var lastTrial = 0L
    private var profileComplete = false
    private var previousBytes = 0L
    private val delivered = AtomicLong()
    private val hedgeBudget=com.nuvio.tv.core.network.adaptive.HedgeBudget()
    private val alternateWins=java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var useAlternate=false
    @Volatile private var transportChanged=false
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
        hardClose(); closed.set(false);FlowAdaptiveRuntime.protocol=null;FlowAdaptiveRuntime.peer=null; spec=dataSpec; position=dataSpec.position; next=position
        rangeBudget.reset();memoryAt=Long.MIN_VALUE;memoryPressure=false
        hedgeBudget.reset();delivered.set(0);previousBytes=0;lastTick=SystemClock.elapsedRealtime();lastTrial=lastTick;profileComplete=false;modeScores.clear();synchronized(speeds){speeds.clear()}
        var probe=upstream.createDataSource()
        openingSource=probe
        var recoveredProbe=false
        var probePermit=false
        try {
            permits.acquire();probePermit=true
            try {probe.open(dataSpec.buildUpon().setLength(1).build())}
            catch(error:IOException) {
                if(readerClosed.get())throw java.io.InterruptedIOException("Opening cancelled")
                runCatching{probe.close()}
                probe=recoveryFactory.createDataSource();openingSource=probe
                probe.open(dataSpec.buildUpon().setLength(1).build());recoveredProbe=true
            }
            uri=probe.uri ?: dataSpec.uri
            val range=probe.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range",true) }?.value?.firstOrNull()
            val total=range?.substringAfterLast('/')?.toLongOrNull()
            if(total==null || total<=position) {
                probe.close()
                val single=(if(recoveredProbe)alternateFactory else upstream).createDataSource();listeners.forEach(single::addTransferListener)
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
            useAlternate=recoveredProbe || (recent && preferences.getBoolean("$key.independent",false))
            alternateWins.set(0)
            FlowAdaptiveRuntime.protocol=null;FlowAdaptiveRuntime.peer=null
        } finally { openingSource=null;runCatching { probe.close() };if(probePermit)permits.release() }
        if(budgetMb()<16) {
            val single=(if(recoveredProbe)alternateFactory else upstream).createDataSource();listeners.forEach(single::addTransferListener)
            fallback=single;FlowAdaptiveRuntime.mode="Single (memory fallback)"
            return single.open(dataSpec)
        }
        schedule()
        return end-position
    }
    private var memoryAt = Long.MIN_VALUE
    private var cachedBudget = 16
    private val rangeBudget=com.nuvio.tv.core.network.adaptive.SessionRangeBudget()
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
        // Capture once; ordinary chunk allocations must not lower their own ceiling.
        cachedBudget=rangeBudget.update(minOf(96L,heapRoom/MB/2,systemRoom/MB/2).toInt(),memoryPressure)
        return cachedBudget
    }
    private fun mode(): AdaptivePolicy.Mode = if(adaptive) policy.mode else AdaptivePolicy.Mode(fixedWorkers.coerceIn(1,4),fixedChunkMb.coerceIn(8,16))
    private fun schedule() {
        next=maxOf(next,position)
        val m=mode()
        val capacity=(budgetMb()/m.chunkMb).coerceIn(1,m.workers)
        FlowAdaptiveRuntime.reservedMb=(budgetMb()+m.chunkMb).coerceAtMost(112)
        FlowAdaptiveRuntime.mode="${capacity}×${m.chunkMb} MB${if(adaptive) " · Auto" else " · Fixed"}"
        if(capacity<m.workers) {
            FlowAdaptiveRuntime.mode+=" (requested ${m.workers}×${m.chunkMb}; memory limited)"
            FlowAdaptiveRuntime.reason="Range memory budget ${budgetMb()} MB"
        }
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
    private fun launch(chunk: Chunk, hedge: Boolean): Boolean {
        if(hedge && !hedgePermit.tryAcquire()) return false
        chunk.running.incrementAndGet()
        val requestMode=FlowAdaptiveRuntime.mode
        workers.execute {
            var acquired=false
            try {
                permits.acquire();acquired=true
                if(closed.get() || chunk.cancelled.get() || chunk.result.isDone) return@execute
                var last: Exception?=null
                for(attempt in 0..2) {
                    if(closed.get() || chunk.cancelled.get() || chunk.result.isDone) break
                    if(hedge) {if(!hedgeBudget.tryHedge())break} else hedgeBudget.normalStarted()
                    val source=(if(hedge || attempt>0)recoveryFactory else if(useAlternate)alternateFactory else upstream).createDataSource();chunk.sources.add(source)
                    listeners.forEach(source::addTransferListener)
                    val start=SystemClock.elapsedRealtime()
                    var attemptBytes=0
                    var outcome="cancelled"
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
                                offset+=n;attemptBytes=offset
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
                            if(chunk.result.complete(bytes)) {
                                chunk.sources.filter { it!==source }.forEach { runCatching { it.close() } }
                                if(adaptive && hedge && !useAlternate && alternateWins.incrementAndGet()>=2) {
                                    useAlternate=true;transportChanged=true
                                    FlowAdaptiveRuntime.reason="Independent connection recovery won slow ranges"
                                }
                            }
                            outcome="complete"
                            return@execute
                        }
                    } catch(e:Exception) {last=e;outcome="failed"} finally {
                        android.util.Log.i("FlowRange","range=${chunk.start}-${chunk.start+chunk.size-1} bytes=$attemptBytes ms=${SystemClock.elapsedRealtime()-start} mode=$requestMode hedge=$hedge attempt=$attempt outcome=$outcome protocol=${(source as? FlowObservedConnection)?.details?.protocol ?: "not_exposed"} peer=${(source as? FlowObservedConnection)?.details?.peer ?: "not_exposed"} host=${uri?.host}")
                        if(source is FlowObservedConnection) {
                            FlowAdaptiveRuntime.protocol=source.details.protocol
                            FlowAdaptiveRuntime.peer=source.details.peer
                        }
                        chunk.sources.remove(source);runCatching { source.close() }
                    }
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
        return true
    }
    @Synchronized override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if(length==0)return 0
        if(readerClosed.get() || closed.get())throw IOException("Source closed")
        fallback?.let{return it.read(buffer,offset,length)}
        if(closed.get() || readerClosed.get())throw IOException("Source closed")
        if(position>=end)return C.RESULT_END_OF_INPUT
        schedule()
        val c=chunks.firstEntry()?.value ?: throw IOException("No scheduled range")
        while(!c.result.isDone) {
            if(closed.get() || readerClosed.get())throw IOException("Source closed")
            tick()
            val elapsed=SystemClock.elapsedRealtime()-c.started
            val median=synchronized(speeds){speeds.sorted().let{if(it.size>=3)it[it.size/2] else null}}
            if(!c.hedged && chunks.values.sumOf{it.sources.size}>=3 && budgetMb()>=(chunks.size+1)*mode().chunkMb && median!=null && elapsed>2000 &&
                (c.count.get()*8000/elapsed<median/2 || elapsed>c.size*16000L/median)) {
                c.hedged=launch(c,true)
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
            val speed=FlowAdaptiveRuntime.playbackSpeed.coerceAtLeast(0.1)
            val buffered=FlowAdaptiveRuntime.bufferSeconds/speed
            val bitrate=FlowAdaptiveRuntime.durationMs.takeIf{it>0}?.let{(fileLength*8000.0/it*speed).toLong()}
            if(transportChanged){modeScores.clear();profileComplete=false;lastTrial=now;transportChanged=false}
            val scores=modeScores.getOrPut(policy.index){ArrayDeque()}
            if(rate>0) {scores.addLast(rate);while(scores.size>8)scores.removeFirst()}
            val before=policy.index
            val d=policy.evaluate(now,bitrate,buffered,rate.takeIf{it>0},budgetMb(),memoryPressure)
            if(before!=policy.index)lastTrial=now
            if(!profileComplete && d.reason=="Stable" && now-lastTrial>=20_000 && buffered>=24 && !memoryPressure) {
                // Reuse real, ordered media downloads for mode trials. Never stall playback for a synthetic probe.
                val choice=com.nuvio.tv.core.network.adaptive.ModeComparison.select(modeScores.mapValues{it.value.toList()},budgetMb())
                if(choice!=null) {
                    if(choice.index!=policy.index)policy.selectObservedMode(choice.index,now)
                    profileComplete=choice.complete
                }
                lastTrial=now
            }
            if(d.reprobe) {
                val best=modeScores.filterValues{it.size>=3}.maxByOrNull{(_,v)->v.sorted()[v.size/2]}?.key
                if(best!=null && best!=policy.index) policy.selectObservedMode(best,now)
            }
            if(before!=policy.index || d.reprobe || memoryPressure) FlowAdaptiveRuntime.reason=if(before!=policy.index) "Mode comparison / delivery adjustment" else d.reason
            // Save observed profiles only after sufficient active transfer history.
            if(synchronized(speeds){speeds.size>=6} && d.reason=="Stable" && buffered>=15 && bitrate!=null && rate>=bitrate*1.5) {
                val key=uri?.host ?: ""
                val bestKnown=modeScores.filterValues{it.size>=3}.maxByOrNull{(_,v)->v.sorted()[v.size/2]}?.key ?: policy.index
                preferences.edit().putInt(key,bestKnown).putBoolean("$key.independent",useAlternate).putLong("$key.updated",System.currentTimeMillis()).apply()
            }
        }
        lastTick=now;previousBytes=bytes
    }
    override fun close() {
        readerClosed.set(true)
        openingSource?.let{runCatching{it.close()}}
        fallback?.let{runCatching{it.close()}}
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

internal class FlowConnectionInfo {
    @Volatile var protocol: String? = null
    @Volatile var peer: String? = null
}
@UnstableApi
internal class FlowObservedConnection(
    private val source:DataSource,
    private val snapshot:FlowConnectionInfo,
    private val refresh:(FlowConnectionInfo)->Unit = {}
):DataSource by source {
    // DataSource's Java default method must be forwarded explicitly by Kotlin wrappers.
    override fun getResponseHeaders(): Map<String,List<String>> = source.responseHeaders
    val details:FlowConnectionInfo get() {refresh(snapshot);return snapshot}
    override fun open(spec:DataSpec):Long {
        val length=source.open(spec)
        val observed=details
        FlowAdaptiveRuntime.protocol=observed.protocol;FlowAdaptiveRuntime.peer=observed.peer
        return length
    }
}
