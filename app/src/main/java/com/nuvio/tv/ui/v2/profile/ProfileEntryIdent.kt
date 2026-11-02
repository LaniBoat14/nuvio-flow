package com.nuvio.tv.ui.v2.profile

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nuvio.tv.R
import com.nuvio.tv.core.util.StartupLatencyTrace
import com.nuvio.tv.ui.brand.ArcIgnitionView
import com.nuvio.tv.ui.brand.ArcIntroSound
import com.nuvio.tv.ui.brand.ArcIntroStage
import com.nuvio.tv.ui.brand.ArcPalette
import com.nuvio.tv.ui.brand.ArcStartHandoff
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

object EntryIdentPolicy {
    /** The spark has reached the bottom of the cut by now, so the exit can begin. */
    const val MinimumCycleMs = 4_600L
    const val RecoveryTimeoutMs = 10_000L
    /** The mark fades out first, then the dark stage dissolves into Home; the sound ends as Home is fully there. */
    const val ExitMs = 1_400L
    /** The entrance gets as long as it would have taken to play before the still mark stands in for it. */
    const val FirstFrameLimitMs = MinimumCycleMs
    /** A still mark that stands in late is held this long at least, so it never only flashes up. */
    const val FallbackHoldMs = 1_500L
    /** Art that is ready at the limit is one vsync from its first frame, so it gets a little longer. */
    const val ReadyGraceMs = 250L
    const val FirstFrameRecheckMs = 50L
    fun shouldFinish(elapsedMs: Long, homeReady: Boolean): Boolean =
        elapsedMs >= RecoveryTimeoutMs || (homeReady && elapsedMs >= MinimumCycleMs)

    enum class FirstFrame { Wait, Started, Fallback }

    /** A frame already drawn counts even when its message has not reached the main thread yet. */
    fun firstFrame(elapsedMs: Long, stage: ArcIntroStage?): FirstFrame = when (stage) {
        ArcIntroStage.Drawn -> FirstFrame.Started
        ArcIntroStage.Failed -> FirstFrame.Fallback
        ArcIntroStage.Ready -> if (elapsedMs >= FirstFrameLimitMs + ReadyGraceMs) FirstFrame.Fallback else FirstFrame.Wait
        else -> if (elapsedMs >= FirstFrameLimitMs) FirstFrame.Fallback else FirstFrame.Wait
    }

    /** How long the ident holds once it shows something, counted from then; [begunAtMs] is from the ident appearing. */
    fun holdMs(begunAtMs: Long, fallback: Boolean): Long =
        if (fallback) max(FallbackHoldMs, MinimumCycleMs - begunAtMs) else MinimumCycleMs

    fun exitOpacity(elapsedMs: Long, durationMs: Long = ExitMs): Float =
        if (elapsedMs >= durationMs) 0f else 1f - FastOutSlowInEasing.transform(elapsedMs.coerceAtLeast(0L).toFloat() / durationMs)

    /** Home is about to show the ident: it is pending, or it starts on entry as soon as Home is composed. */
    fun introComing(
        pending: Boolean,
        nuvioV2: Boolean,
        introEnabled: Boolean,
        startsOnEntry: Boolean,
        playbackUiActive: Boolean
    ): Boolean = !playbackUiActive && (pending || (nuvioV2 && introEnabled && startsOnEntry))

    /** The start window's mark stays under the entrance until it has drawn; the still fallback replaces it. */
    fun showsStartPicture(handoff: Boolean, failed: Boolean): Boolean = handoff && !failed

    /** What is left of the entrance's fade-in [sinceStartMs] after it started, with a frame to spare. */
    fun fadeInLeftMs(sinceStartMs: Long): Long =
        ((ArcStartHandoff.CrossfadeSeconds * 1000f).toLong() + FadeInSpareMs - sinceStartMs).coerceAtLeast(0L)
    private const val FadeInSpareMs = 50L
}

/** Fades the still mark out on the frame clock; the animator duration scale can shorten the fade but never stretch it. */
internal suspend fun fadeOutOnFrameClock(onOpacity: (Float) -> Unit) {
    val scale = (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f).coerceIn(0f, 1f)
    val durationMs = (EntryIdentPolicy.ExitMs * scale).toLong()
    val startedAtMs = withFrameMillis { it }
    while (true) {
        val opacity = EntryIdentPolicy.exitOpacity(withFrameMillis { it } - startedAtMs, durationMs)
        onOpacity(opacity)
        if (opacity <= 0f) return
    }
}

/**
 * The start window's picture on Android 8 to 11 (the launcher mark where the entrance puts its logo). It stays on
 * screen through the loading boxes until the first real screen or the entrance's first frame takes its place.
 */
@Stable
class StartPicture(private val drawable: Drawable?, private val onEnd: () -> Unit = {}) {
    var showing by mutableStateOf(drawable != null)
        private set

    fun end() {
        if (!showing) return
        showing = false
        onEnd()
    }

    internal fun Modifier.drawPicture(): Modifier = drawBehind {
        val picture = drawable ?: return@drawBehind
        picture.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
        drawIntoCanvas { picture.draw(it.nativeCanvas) }
    }
}

/** Draws the start window's picture while it is showing, otherwise [color]. */
fun Modifier.startPictureOr(picture: StartPicture?, color: Color): Modifier =
    if (picture != null && picture.showing) with(picture) { drawPicture() } else background(color)

/** Draws the start window's picture while it is showing, otherwise nothing. */
fun Modifier.startPicture(picture: StartPicture?): Modifier =
    if (picture != null && picture.showing) with(picture) { drawPicture() } else this

/** Presentation-only handoff, scoped to this Activity. No profile/session or navigation ownership. */
@Stable
class ProfileEntryState {
    var pending by mutableStateOf(false)
        private set
    var homeReady by mutableStateOf(false)
    var revealRequested by mutableStateOf(false)
        private set
    var revealing by mutableStateOf(false)
        private set
    /** Set once the intro has been on screen (or is switched off), not when it was only asked for. */
    var hasRunThisSession = false
        private set
    fun start(enabled: Boolean = true) {
        if (!enabled) hasRunThisSession = true
        homeReady = false; revealRequested = false; revealing = false; pending = enabled
    }
    fun markShown() { if (pending) hasRunThisSession = true }
    /** An intro that was asked for but ended before its first frame (Home rebuilt under it) is started again. */
    fun shouldStartOnEntry(): Boolean = !hasRunThisSession && !pending
    fun requestReveal() { if (homeReady) revealRequested = true }
    fun beginReveal() { revealing = true }
    fun finish() { pending = false }
}

val LocalProfileEntryState = staticCompositionLocalOf<ProfileEntryState?> { null }

/** The Arc entrance plays on its own surface and thread; Compose only owns timing, Back and the exit. */
@Composable
fun ProfileEntryIdent(
    state: ProfileEntryState,
    soundEnabled: Boolean = false,
    startPicture: StartPicture? = null,
    onReturnToProfiles: () -> Unit
) {
    val context = LocalContext.current
    val handoff = remember { startPicture?.showing == true }
    var opacity by remember { mutableFloatStateOf(1f) }
    var sound by remember { mutableStateOf<ArcIntroSound?>(null) }
    // Read by the intro thread, which starts the sound with the first frame it draws.
    val soundForFrame = remember { AtomicReference<ArcIntroSound?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        val stillIntro = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        if (!soundEnabled || stillIntro) return@LaunchedEffect
        // Owned by this coroutine, so leaving at any moment, even the frame it is made in, always lets it go.
        val created = ArcIntroSound(context, lifecycle)
        sound = created
        soundForFrame.set(created)
        try {
            awaitCancellation()
        } finally {
            soundForFrame.set(null)
            created.fadeOutAndRelease(ArcIntroSound.FadeMs)
        }
    }
    val shownAt = remember { SystemClock.uptimeMillis().also { StartupLatencyTrace.cold("intro_ident") } }
    fun sinceShown() = SystemClock.uptimeMillis() - shownAt
    var leaving by remember { mutableStateOf(false) }
    var minimumComplete by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    var failed by remember { mutableStateOf(false) }
    var backdrop by remember { mutableStateOf(true) }
    var surface by remember { mutableStateOf<ArcIgnitionView?>(null) }
    // The theme can still settle (for example membership themes) before the first frame; never after, also while the
    // first frame's message is still on its way to the main thread.
    var startedPalette by remember { mutableStateOf<ArcPalette?>(null) }
    val palette = startedPalette ?: surface?.takeIf { it.stage == ArcIntroStage.Drawn }?.palette ?: NuvioTheme.arcPalette
    val begun = started || failed
    val pictureBackdrop = remember(startPicture) { startPicture?.let { with(it) { Modifier.drawPicture() } } ?: Modifier }
    fun startFrom(view: ArcIgnitionView) {
        if (surface !== view || started || failed) return
        if (!leaving) sound?.play()
        startedPalette = view.palette
        started = true
        startedAt = SystemClock.uptimeMillis()
        StartupLatencyTrace.cold("intro_started", "ident=+${sinceShown()} ms")
    }
    fun fallBack(reason: String) {
        if (failed) return
        failed = true
        StartupLatencyTrace.cold("intro_fallback", "$reason${if (started) " after start" else ""} ident=+${sinceShown()} ms")
    }
    DisposableEffect(startPicture) { onDispose { startPicture?.end() } }
    LaunchedEffect(begun) {
        if (!begun) return@LaunchedEffect
        startPicture?.end()
        state.markShown()
        delay(EntryIdentPolicy.holdMs(sinceShown(), fallback = failed))
        minimumComplete = true
    }
    LaunchedEffect(minimumComplete, state.homeReady, state.revealRequested) {
        if (state.revealRequested || EntryIdentPolicy.shouldFinish(if (minimumComplete) EntryIdentPolicy.MinimumCycleMs else 0L, state.homeReady)) {
            leaving = true
        }
    }
    LaunchedEffect(Unit) {
        delay(EntryIdentPolicy.FirstFrameLimitMs)
        while (!started && !failed && !leaving) {
            val view = surface
            when (EntryIdentPolicy.firstFrame(sinceShown(), view?.stage)) {
                EntryIdentPolicy.FirstFrame.Started -> { view?.let(::startFrom); break }
                EntryIdentPolicy.FirstFrame.Fallback -> { fallBack("limit stage=${view?.stage?.name ?: "no surface"}"); break }
                EntryIdentPolicy.FirstFrame.Wait -> delay(EntryIdentPolicy.FirstFrameRecheckMs)
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(EntryIdentPolicy.RecoveryTimeoutMs)
        leaving = true // Expose Home's existing loading/error/retry and navigation surfaces.
    }
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        startPicture?.end()
        state.beginReveal()
        val view = surface
        sound?.fadeOutAndRelease(EntryIdentPolicy.ExitMs)
        if (view != null && !failed && started) {
            // Once its fade-in is over the surface is opaque until it fades out, so Home can sit behind it from here on.
            delay(EntryIdentPolicy.fadeInLeftMs(SystemClock.uptimeMillis() - startedAt))
            backdrop = false
            repeat(2) { withFrameNanos { } }
            withTimeoutOrNull(EntryIdentPolicy.ExitMs + 1_500L) {
                suspendCancellableCoroutine<Unit> { cont -> view.exit(EntryIdentPolicy.ExitMs) { if (cont.isActive) cont.resume(Unit) } }
            }
        } else {
            view?.exit(0L) {}
            withFrameNanos { }
            fadeOutOnFrameClock { opacity = it }
        }
        StartupLatencyTrace.cold("intro_finished", "${if (started && !failed) "animated" else "still"} ident=+${sinceShown()} ms")
        state.finish()
    }
    BackHandler { state.finish(); onReturnToProfiles() }
    val description = stringResource(R.string.cd_nuvio_logo)
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = opacity }
            .then(
                when {
                    !backdrop -> Modifier
                    startPicture != null && EntryIdentPolicy.showsStartPicture(handoff, failed) -> pictureBackdrop
                    else -> Modifier.background(IdentBackdrop)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (failed) {
            BrandWordmark(Modifier.height(72.dp), contentDescription = description)
        } else {
            key(palette) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        surface?.let { StartupLatencyTrace.cold("intro_restart", "${it.palette.key}>${palette.key} ident=+${sinceShown()} ms") }
                        lateinit var view: ArcIgnitionView
                        view = ArcIgnitionView(
                            context = context,
                            palette = palette,
                            onStarted = { startFrom(view) },
                            onFailed = { reason -> if (surface === view) fallBack(reason) },
                            onFirstFrame = { if (surface === view) soundForFrame.get()?.play() },
                            handoff = handoff
                        )
                        view.also { it.contentDescription = description; surface = it }
                    },
                    onRelease = { if (surface === it) surface = null }
                )
            }
        }
    }
}

private val IdentBackdrop = Color(0xFF03050A)
