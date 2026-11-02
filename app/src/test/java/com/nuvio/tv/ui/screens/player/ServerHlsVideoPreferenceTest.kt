package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.text.TextUtils
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The variant media3 picks from a Jellyfin HLS playlist of a copied Dolby Vision video. */
class ServerHlsVideoPreferenceTest {

    init {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
        mockkStatic(Pair::class)
        every { Pair.create<Any?, Any?>(any(), any()) } answers { pair(firstArg(), secondArg()) }
    }

    private fun pair(first: Any?, second: Any?): Pair<Any?, Any?> = Pair<Any?, Any?>(first, second).also {
        Pair::class.java.getField("first").apply { isAccessible = true }.set(it, first)
        Pair::class.java.getField("second").apply { isAccessible = true }.set(it, second)
    }

    private val context = mockk<Context>(relaxed = true).also { context ->
        every { context.applicationContext } returns context
        every { context.getSystemService(any<String>()) } returns null
    }

    private fun variant(id: String, mimeType: String, codecs: String): Format =
        Format.Builder()
            .setId(id)
            .setSampleMimeType(mimeType)
            .setCodecs(codecs)
            .setWidth(3840)
            .setHeight(2160)
            .setAverageBitrate(15_000_000)
            .setPeakBitrate(15_000_000)
            .build()

    private val dolbyVision = variant("dv", MimeTypes.VIDEO_DOLBY_VISION, "dvh1.08.06")
    private val hevcSdr = variant("hevc", MimeTypes.VIDEO_H265, "hvc1.1.4.L150.B0")
    private val avc = variant("avc", MimeTypes.VIDEO_H264, "avc1.424029")

    private fun supported(primary: Boolean) = RendererCapabilities.create(
        C.FORMAT_HANDLED,
        RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
        RendererCapabilities.TUNNELING_NOT_SUPPORTED,
        RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED,
        if (primary) RendererCapabilities.DECODER_SUPPORT_PRIMARY else RendererCapabilities.DECODER_SUPPORT_FALLBACK
    )

    private val unsupported = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)

    private fun box(support: (String?) -> Int) = object : RendererCapabilities {
        override fun getName() = "video"
        override fun getTrackType() = C.TRACK_TYPE_VIDEO
        override fun supportsFormat(format: Format) = support(format.sampleMimeType)
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
    }

    /** AM9: Dolby Vision decodes, but its decoder is not the first one listed for the codec string. */
    private val am9 = box { mime ->
        when (mime) {
            MimeTypes.VIDEO_DOLBY_VISION -> supported(primary = false)
            else -> supported(primary = true)
        }
    }

    /** HEVC but no Dolby Vision decoder: media3 plays profile 8 on the HEVC decoder. */
    private val hevcOnly = box { mime ->
        when (mime) {
            MimeTypes.VIDEO_DOLBY_VISION -> supported(primary = false)
            MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264 -> supported(primary = true)
            else -> unsupported
        }
    }

    private val avcOnly = box { mime -> if (mime == MimeTypes.VIDEO_H264) supported(primary = true) else unsupported }

    private fun chosen(renderer: RendererCapabilities, serverHls: Boolean, vararg variants: Format): String {
        val params = DefaultTrackSelector.Parameters.Builder().clearViewportSizeConstraints()
        serverHlsVideoMimeTypes(serverHls, emptyList())?.let { params.setPreferredVideoMimeTypes(*it) }
        val selector = DefaultTrackSelector(context, params.build(), AdaptiveTrackSelection.Factory())
        selector.init(TrackSelector.InvalidationListener { }, mockk<BandwidthMeter>(relaxed = true))
        val result = selector.selectTracks(
            arrayOf(renderer),
            TrackGroupArray(TrackGroup(*variants)),
            MediaSource.MediaPeriodId(Any()),
            Timeline.EMPTY
        )
        val selection = result.selections[0] ?: return "none"
        return selection.getFormat(0).id ?: "none"
    }

    @Test
    fun `without the preference the AM9 takes the H264 copy`() {
        assertEquals("avc", chosen(am9, serverHls = false, dolbyVision, avc))
    }

    @Test
    fun `AM9 on a server playlist takes Dolby Vision`() {
        assertEquals("dv", chosen(am9, serverHls = true, dolbyVision, avc))
        assertEquals("dv", chosen(am9, serverHls = true, avc, dolbyVision))
        assertEquals("dv", chosen(am9, serverHls = true, dolbyVision, hevcSdr, avc))
    }

    @Test
    fun `HEVC before H264 when there is no Dolby Vision line`() {
        assertEquals("hevc", chosen(am9, serverHls = true, avc, hevcSdr))
    }

    @Test
    fun `box without a Dolby Vision decoder still keeps the HDR picture`() {
        assertEquals("dv", chosen(hevcOnly, serverHls = true, dolbyVision, hevcSdr, avc))
    }

    @Test
    fun `box without HEVC falls back to H264`() {
        assertEquals("avc", chosen(avcOnly, serverHls = true, dolbyVision, avc))
        assertEquals("avc", chosen(avcOnly, serverHls = true, dolbyVision, hevcSdr, avc))
    }

    @Test
    fun `only server playlists without their own preference get the order`() {
        assertArrayEquals(SERVER_HLS_VIDEO_MIME_ORDER, serverHlsVideoMimeTypes(serverHls = true, current = emptyList()))
        assertNull(serverHlsVideoMimeTypes(serverHls = false, current = emptyList()))
        assertNull(serverHlsVideoMimeTypes(serverHls = true, current = listOf(MimeTypes.VIDEO_H264)))
    }
}
