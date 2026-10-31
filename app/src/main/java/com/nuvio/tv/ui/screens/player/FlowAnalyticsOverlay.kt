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
    var history by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    LaunchedEffect(viewModel, uiState.currentStreamUrl) {
        val samples = ArrayDeque<Triple<Long, Long, Float>>()
        var cpuMs = Process.getElapsedCpuTime()
        var lastMs = SystemClock.elapsedRealtime()
        var lastBuffer = 0f
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
            val info = viewModel.uiState.value.streamInfoData
            rows = buildList {
                add("PLAYBACK" to state)
                add("Position" to "${seconds(player?.currentPosition?.coerceAtLeast(0) ?: 0)} / ${seconds(player?.duration?.coerceAtLeast(0) ?: 0)}")
                add("Rebuffers" to "${diagnostics.rebuffers} · ${seconds(diagnostics.rebufferMs)} total")
                add("SOURCE" to (info?.addonName ?: "—"))
                add("File" to (info?.filename?.take(55) ?: "—"))
                add("Size" to (viewModel.getCurrentFileSizeBytes()?.let(::mb) ?: "Unavailable"))
                add("VIDEO" to "${video?.width ?: "—"} × ${video?.height ?: "—"} · ${video?.frameRate?.takeIf { it > 0 }?.let(::f) ?: "—"} fps")
                add("Format" to (video?.codecs ?: video?.sampleMimeType ?: "Unavailable"))
                add("HDR transfer" to when (video?.colorInfo?.colorTransfer) { 6 -> "PQ / HDR"; 7 -> "HLG"; 3 -> "SDR"; else -> "Not reported" })
                add("Decoder" to (diagnostics.videoDecoder ?: "Not reported"))
                add("Dropped" to "${diagnostics.dropped}")
                add("File bitrate" to (info?.fileBitrate?.takeIf { it > 0 }?.let { "${f(it / 1_000_000f)} Mbps average" } ?: "Unavailable"))
                add("AUDIO" to "${audio?.sampleMimeType ?: "—"} · ${audio?.channelCount ?: "—"} ch · ${audio?.sampleRate ?: "—"} Hz")
                add("Audio decoder" to (diagnostics.audioDecoder ?: "Not reported"))
                add("Underruns" to "${diagnostics.audioUnderruns} · output/passthrough not inferred")
                add("NETWORK" to "Buffer ${f(ahead)} s ahead · ${if (ahead >= lastBuffer) "growing / steady" else "shrinking"}")
                add("HTTP payload 1s" to (one?.let { "${f(it)} Mbps" } ?: "Sampling…"))
                add("HTTP payload ≤5s" to (five?.let { "${f(it)} Mbps" } ?: "Sampling…"))
                add("Fetch activity" to when { observed -> "Receiving player HTTP data"; FlowTransferMeter.active.get() > 0 -> "Open transfer; no bytes in this window"; else -> "No observed HTTP transfer" })
                add("Active transfers" to "${FlowTransferMeter.active.get()} HTTP (not TCP connections)")
                add("Observed host" to (FlowTransferMeter.host ?: "Not observed"))
                add("Load errors" to "${diagnostics.loadErrors}")
                add("Bandwidth est." to (diagnostics.bandwidthEstimate?.let { "${f(it / 1_000_000f)} Mbps · player estimate" } ?: "Unavailable"))
                add("SYSTEM" to "CPU ${f(cpu)}% · one-core basis")
                add("Memory" to "Java ${mb(runtime.totalMemory() - runtime.freeMemory())}/${mb(runtime.maxMemory())} · native ${mb(Debug.getNativeHeapAllocatedSize())}")
            }
            lastBuffer = ahead
            history = (history + (ahead to (one ?: 0f))).takeLast(60)
            delay(1000)
        }
    }
    Column(modifier.width(430.dp).background(Color(0xE6101520), RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("FLOW · LIVE PLAYBACK ANALYTICS", color = Color.White, fontSize = 12.sp)
        rows.forEach { (label, value) ->
            Row {
                Text(label, Modifier.width(110.dp), color = Color(0xFF9BAEC4), fontSize = 9.sp, maxLines = 1)
                Text(value, Modifier.weight(1f), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1)
            }
        }
        Text("Last 60 samples: buffer (cyan) · HTTP Mbps (gold), independent scales", color = Color.LightGray, fontSize = 8.sp)
        Canvas(Modifier.fillMaxWidth().height(32.dp)) {
            fun plot(values: List<Float>, color: Color) {
                val max = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
                for (i in 1 until values.size) drawLine(color,
                    Offset((i-1) * size.width / 59f, size.height * (1 - values[i-1] / max)),
                    Offset(i * size.width / 59f, size.height * (1 - values[i] / max)), 2f)
            }
            plot(history.map { it.first }, Color.Cyan)
            plot(history.map { it.second }, Color(0xFFFFD166))
        }
        Text("HTTP includes player auxiliary requests; bypass/native paths may be unobserved. Back closes.", color = Color.LightGray, fontSize = 8.sp)
    }
}
