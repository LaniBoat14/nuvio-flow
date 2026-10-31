package com.nuvio.tv.ui.screens.player

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal data class FlowDiagnosticSummary(
    val videoDecoder: String?, val audioDecoder: String?, val dropped: Int,
    val audioUnderruns: Int, val loadErrors: Int, val bandwidthEstimate: Long?,
    val rebuffers: Int, val rebufferMs: Long
)

/** Counts bytes delivered through player HTTP sources, never app-wide UID traffic.
 * Native/MPV and bypass paths are not covered; the UI labels absent observations.
 * Multiple concurrent sources share atomics; no networking is initiated here.
 */
@OptIn(UnstableApi::class)
internal object FlowTransferMeter : TransferListener {
    val bytes = AtomicLong(0)
    val active = AtomicInteger(0)
    @Volatile var host: String? = null
    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork) {
            active.incrementAndGet()
            host = source.uri?.host ?: dataSpec.uri.host
        }
    }
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork && bytesTransferred > 0) bytes.addAndGet(bytesTransferred.toLong())
    }
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork) active.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun FlowAnalyticsOverlay(viewModel: PlayerViewModel, uiState: PlayerUiState, modifier: Modifier = Modifier) {
    var rows by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var indicators by remember { mutableStateOf(emptyMap<String, Color>()) }
    var history by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    LaunchedEffect(viewModel, uiState.currentStreamUrl) {
        val samples = ArrayDeque<Triple<Long, Long, Float>>()
        var cpuMs = Process.getElapsedCpuTime()
        var lastMs = SystemClock.elapsedRealtime()
        var lastDropped = 0
        var lastUnderruns = 0
        var lastErrors = 0
        var previousPlayer: Player? = null
        while (true) {
            val player = viewModel.exoPlayer
            if (player !== previousPlayer) {
                samples.clear()
                history = emptyList()
                previousPlayer = player
            }
            val now = SystemClock.elapsedRealtime()
            val bytes = FlowTransferMeter.bytes.get()
            val ahead = player?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0) / 1000f } ?: 0f
            val previous = samples.lastOrNull()
            samples.addLast(Triple(now, bytes, ahead))
            while (samples.size > 6) samples.removeFirst()
            fun rate(sample: Triple<Long, Long, Float>?): Float? = sample?.let {
                if (now <= it.first) null else (bytes - it.second).coerceAtLeast(0) * 8f / (now - it.first) / 1000f
            }
            val one = rate(previous)
            val five = rate(samples.firstOrNull()?.takeIf { samples.size >= 2 })
            val diagnostics = viewModel.flowDiagnostics()
            val video = player?.videoFormat
            val audio = player?.audioFormat
            val cpuNow = Process.getElapsedCpuTime()
            val cpu = (cpuNow - cpuMs).coerceAtLeast(0) * 100f / (now - lastMs).coerceAtLeast(1)
            cpuMs = cpuNow; lastMs = now
            val runtime = Runtime.getRuntime()
            fun f(value: Float) = String.format(Locale.US, "%.1f", value)
            fun seconds(value: Long) = "${f(value / 1000f)} s"
            fun mb(value: Long) = "${value / (1024 * 1024)} MB"
            val observed = one != null && one > 0f
            val state = when (player?.playbackState) {
                Player.STATE_BUFFERING -> "Buffering"
                Player.STATE_ENDED -> "Ended"
                Player.STATE_READY -> if (player.isPlaying) "Playing" else "Paused / ready"
                Player.STATE_IDLE -> "Idle"
                else -> "Other engine — limited metrics"
            }
            val info = viewModel.flowStreamInfo()
            val green = Color(0xFF70D99B)
            val amber = Color(0xFFFFCA69)
            val red = Color(0xFFFF7777)
            indicators = buildMap {
                put("Dropped", if (diagnostics.dropped > lastDropped) amber else green)
                put("Underruns", if (diagnostics.audioUnderruns > lastUnderruns) amber else green)
                if (player != null) put("Buffer", when {
                    player.playbackState == Player.STATE_BUFFERING -> red
                    ahead < 10f && player.isPlaying -> amber
                    else -> green
                })
                put("Load errors", if (diagnostics.loadErrors > lastErrors) red else green)
            }
            lastDropped = diagnostics.dropped
            lastUnderruns = diagnostics.audioUnderruns
            lastErrors = diagnostics.loadErrors
            rows = buildList {
                add("SOURCE" to "")
                add("Add-on" to (info?.addonName ?: "Unavailable"))
                add("Server" to (FlowTransferMeter.host ?: "Unavailable"))
                add("File" to (info?.filename ?: "Unavailable"))
                add("Size" to (viewModel.getCurrentFileSizeBytes()?.let { "${f(it / 1_073_741_824f)} GB" } ?: "Unavailable"))
                add("VIDEO" to "")
                add("Video" to "${video?.width ?: "—"} × ${video?.height ?: "—"} · ${(video?.frameRate?.takeIf { it > 0 } ?: info.videoFrameRate?.takeIf { it > 0 })?.let(::f) ?: "—"} fps")
                add("Codec" to (video?.codecs ?: video?.sampleMimeType ?: "Unavailable"))
                add("HDR" to when {
                    video?.sampleMimeType == "video/dolby-vision" || video?.codecs?.startsWith("dv") == true -> "Dolby Vision"
                    video?.colorInfo?.colorTransfer == 6 -> "PQ / HDR"
                    video?.colorInfo?.colorTransfer == 7 -> "HLG"
                    video?.colorInfo?.colorTransfer == 3 -> "SDR"
                    else -> "Unavailable"
                })
                add("File bitrate" to (info?.fileBitrate?.takeIf { it > 0 }?.let { "${f(it / 1_000_000f)} Mbps average" } ?: "Unavailable"))
                add("Decoder" to (diagnostics.videoDecoder ?: "Unavailable"))
                add("Dropped" to "${diagnostics.dropped} frames")
                add("AUDIO" to "")
                add("Audio" to "${audio?.sampleMimeType ?: "—"} · ${audio?.channelCount ?: "—"} ch · ${audio?.sampleRate?.takeIf { it > 0 }?.div(1000f)?.let(::f) ?: "—"} kHz")
                add("A. bitrate" to (audio?.bitrate?.takeIf { it > 0 }?.let { "${f(it / 1_000_000f)} Mbps · track" } ?: "Not reported"))
                add("A. decoder" to (diagnostics.audioDecoder ?: "Unavailable"))
                add("Output mode" to "Unavailable")
                add("Underruns" to "${diagnostics.audioUnderruns}")
                add("NETWORK" to "")
                add("Buffer" to if (player != null) "${f(ahead)} s ahead" else "Unavailable")
                add("HTTP speed" to (five?.let { "${f(it)} Mbps · ${if (observed) "receiving" else "no bytes"}" } ?: "Sampling…"))
                add("Transfers" to "${FlowTransferMeter.active.get()} active HTTP requests")
                add("Rebuffers" to "${diagnostics.rebuffers} · ${seconds(diagnostics.rebufferMs)}")
                add("Load errors" to "${diagnostics.loadErrors}")
                add("SYSTEM" to "")
                add("App CPU" to "${f(cpu)}% · one-core basis")
                add("Memory" to "Java ${mb(runtime.totalMemory() - runtime.freeMemory())}/${mb(runtime.maxMemory())} · native ${mb(Debug.getNativeHeapAllocatedSize())}")
            }
            history = (history + (ahead to (one ?: 0f))).takeLast(60)
            delay(1000)
        }
    }
    Column(
        modifier.width(310.dp)
            .background(Color(0xB818202B), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        rows.forEach { (label, value) ->
            if (value.isEmpty()) {
                Text(label, color = Color(0xFFD6DDE7), fontSize = 11.sp)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.28f)))
            } else {
                Row {
                    Box(Modifier.width(10.dp).height(13.dp)) {
                        indicators[label]?.let { color ->
                            Box(Modifier.padding(top = 4.dp).size(5.dp).background(color, RoundedCornerShape(50)))
                        }
                    }
                    Text(label, Modifier.width(72.dp), color = Color(0xFFD6DDE7), fontSize = 9.sp, maxLines = 1)
                    Text(value, Modifier.weight(1f), color = Color.White, fontSize = 9.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (label == "Load errors") {
                Text("60 s · buffer (cyan) / HTTP Mbps (gold) · separate scales", color = Color.LightGray, fontSize = 8.sp)
                Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                    fun plot(values: List<Float>, color: Color) {
                        val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
                        for (i in 1 until values.size) drawLine(color,
                            Offset((i-1) * size.width / 59f, size.height * (1 - values[i-1] / max)),
                            Offset(i * size.width / 59f, size.height * (1 - values[i] / max)), 2f)
                    }
                    plot(history.map { it.first }, Color.Cyan)
                    plot(history.map { it.second }, Color(0xFFFFD166))
                }
            }
        }
        Text("HTTP includes auxiliary requests; native paths may be unobserved.", color = Color.LightGray, fontSize = 8.sp)
    }
}
