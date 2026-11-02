package com.nuvio.tv.ui.brand

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.nuvio.tv.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos

/**
 * The entrance's sound, 5.1 AAC that Android downmixes where the output has fewer channels. Started on the first
 * drawn frame so the discharge lands with the picture; never takes audio focus, so it stops as soon as the app
 * leaves the screen, also during the fade. The player is built, driven and released on its own thread.
 */
class ArcIntroSound(context: Context, private val lifecycle: Lifecycle) {
    private val gate = SoundGate()
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("ArcIntroSound").apply { start() }
    private val audio = Handler(thread.looper)
    // Only touched on the sound thread.
    private var player: ExoPlayer? = null
    // Watches for as long as the sound lives, also during the tail that outlasts the intro.
    private val stopObserver = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) release() }

    init {
        lifecycle.addObserver(stopObserver)
        val app = context.applicationContext
        audio.post { if (!gate.released) player = build(app) }
    }

    private fun build(app: Context): ExoPlayer? = runCatching {
        val audioOnly = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(MediaCodecAudioRenderer(app, MediaCodecSelector.DEFAULT, handler, audioListener))
        }
        ExoPlayer.Builder(app, audioOnly)
            .setLooper(thread.looper)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false
            )
            .build()
            .apply {
                val uri = Uri.Builder()
                    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                    .authority(app.packageName)
                    .path(R.raw.arc_intro_sound.toString())
                    .build()
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
    }.onFailure { Log.w(Tag, "sound unavailable", it) }.getOrNull()

    /** Safe from any thread; starts the sound once, never after the fade has begun. */
    fun play() {
        if (gate.released) return
        audio.post { if (gate.play()) player?.play() }
    }

    /**
     * Fades out along an equal-power curve and then releases, independent of the screen that started it, so the tail
     * keeps ringing while Home appears. Repeated calls keep the fade already running.
     */
    fun fadeOutAndRelease(durationMs: Long) {
        if (gate.released) return
        audio.post {
            val p = player
            if (p == null) { release(); return@post }
            if (!gate.fade()) return@post
            val start = p.volume
            val begin = SystemClock.uptimeMillis()
            val step = object : Runnable {
                override fun run() {
                    if (gate.released) return
                    val t = ((SystemClock.uptimeMillis() - begin).toFloat() / durationMs).coerceIn(0f, 1f)
                    p.volume = start * cos(t * PI.toFloat() / 2f)
                    if (t >= 1f) release() else audio.postDelayed(this, FadeStepMs)
                }
            }
            step.run()
        }
    }

    fun release() {
        if (!gate.release()) return
        if (Looper.myLooper() == Looper.getMainLooper()) lifecycle.removeObserver(stopObserver)
        else main.post { lifecycle.removeObserver(stopObserver) }
        audio.post {
            player?.release()
            player = null
            thread.quitSafely()
        }
    }

    /** Which calls still reach the player: one start, none once the fade or the release has begun. */
    internal class SoundGate {
        private val releasedFlag = AtomicBoolean(false)
        private var played = false
        private var fading = false
        val released: Boolean get() = releasedFlag.get()
        fun play(): Boolean {
            if (released || fading || played) return false
            played = true
            return true
        }
        fun fade(): Boolean {
            if (released || fading) return false
            fading = true
            return true
        }
        fun release(): Boolean = releasedFlag.compareAndSet(false, true)
    }

    companion object {
        const val FadeMs = 1_500L
        private const val FadeStepMs = 20L
        private const val Tag = "ArcIntroSound"
    }
}
