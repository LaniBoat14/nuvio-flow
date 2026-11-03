package com.nuvio.tv.core.player.thumbnail

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The hardware decoder's output surface. Each picture is shrunk on the GPU in one pass to [w] x [h] (16 bilinear taps,
 * every pixel of the block), tone-mapped with thumbcolor's tables when [luts] are given, and read back as RGBA rows
 * top first. Everything GL runs on its own thread; one picture at a time.
 */
internal class GlThumbShrink(
    private val w: Int,
    private val h: Int,
    private val luts: ToneLuts?,
    private val hlg: Boolean,
    private val bt2020: Boolean,
) {
    private val thread = HandlerThread("ThumbGl").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var texture: SurfaceTexture
    lateinit var surface: Surface
        private set
    private var program = 0
    private var fbo = 0
    private var vbo = 0
    private var oesTex = 0
    private val lutTex = IntArray(3)
    private val matrix = FloatArray(16)
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    private var armed: CountDownLatch? = null

    init {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                setUp()
            } catch (t: Throwable) {
                failure = t
            }
            ready.countDown()
        }
        ready.await()
        failure?.let {
            release()
            throw it
        }
    }

    /** Call before the decoder renders the picture; then [await] it. */
    fun arm(): CountDownLatch {
        val latch = CountDownLatch(1)
        handler.post { armed = latch }
        return latch
    }

    /** The picture's RGBA rows, or null when none was drawn in time. Valid until the next [arm]. */
    fun await(latch: CountDownLatch, timeoutMs: Long): ByteBuffer? =
        if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) pixels else null

    fun release() {
        val done = CountDownLatch(1)
        val posted = handler.post {
            runCatching { if (::surface.isInitialized) surface.release() }
            runCatching { if (::texture.isInitialized) texture.release() }
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                }
            }
            done.countDown()
        }
        if (posted) done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no OpenGL ES 3 config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent" }

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        oesTex = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        texture = SurfaceTexture(oesTex)
        texture.setOnFrameAvailableListener({ draw() }, handler)
        surface = Surface(texture)

        GLES30.glGenTextures(1, ids, 0)
        val target = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, target, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "framebuffer" }
        program = buildProgram()
        if (luts != null) {
            GLES30.glGenTextures(3, lutTex, 0)
            val side = ToneLuts.SIDE
            for ((k, table) in listOf(luts.lin, luts.scale, luts.oetf).withIndex()) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1 + k)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex[k])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                val fb = ByteBuffer.allocateDirect(table.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                fb.put(table).position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, side, side, 0, GLES30.GL_RED, GLES30.GL_FLOAT, fb)
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val qb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        qb.position(0)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, qb, GLES30.GL_STATIC_DRAW)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GL set-up" }
    }

    private fun draw() {
        val latch = armed
        // A picture nobody waits for (one that came after its time ran out) is taken off the surface and dropped.
        texture.updateTexImage()
        if (latch == null) return
        armed = null
        texture.getTransformMatrix(matrix)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uTex"), 1, false, matrix, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uSrc"), 0)
        // A quarter of the block in texture units: taps at -1.5 .. 1.5 of it cover the whole block.
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uTexel"),
            kotlin.math.abs(matrix[0]) / (4f * w), kotlin.math.abs(matrix[5]) / (4f * h))
        if (luts != null) {
            for (k in 0 until 3) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1 + k)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex[k])
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, LUT_NAMES[k]), 1 + k)
            }
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uInvPeak"), luts.invPeak)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        pixels.clear()
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        if (GLES30.glGetError() == GLES30.GL_NO_ERROR) latch.countDown()
    }

    private fun buildProgram(): Int {
        val vs = """#version 300 es
            uniform mat4 uTex;
            layout(location = 0) in vec2 aPos;
            out vec2 vUv;
            void main() {
              vUv = (uTex * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
              gl_Position = vec4(aPos.x, -aPos.y, 0.0, 1.0);
            }"""
        val average = """
            vec3 average() {
              vec4 s = vec4(0.0);
              for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++)
                s += texture(uSrc, vUv + uTexel * vec2(float(i) - 1.5, float(j) - 1.5));
              return clamp(s.rgb / 16.0, 0.0, 1.0);
            }"""
        val head = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp int;
            uniform samplerExternalOES uSrc;
            uniform vec2 uTexel;
            in vec2 vUv;
            out vec4 o;
            $average"""
        val fsPlain = "$head\nvoid main() { o = vec4(average(), 1.0); }"
        val side = ToneLuts.SIDE
        val size = ToneLuts.SIZE
        val fsTone = """$head
            uniform highp sampler2D uLin;
            uniform highp sampler2D uScale;
            uniform highp sampler2D uOetf;
            uniform float uInvPeak;
            float at(highp sampler2D t, int i) { return texelFetch(t, ivec2(i % $side, i / $side), 0).r; }
            int li(float v) { return v > 0.0 ? min(int(sqrt(v) * ${size - 1}.0 + 0.5), ${size - 1}) : 0; }
            float lin(float c) { return at(uLin, clamp(int(c * 65535.0) >> 4, 0, ${size - 1})); }
            void main() {
              vec3 c = average();
              vec3 L = vec3(lin(c.r), lin(c.g), lin(c.b));
              ${if (hlg) "float ys = dot(L, vec3(0.2627, 0.6780, 0.0593)); L *= ys > 1e-7 ? 1000.0 * pow(ys, 0.2) : 0.0;" else ""}
              float m = max(L.r, max(L.g, L.b));
              L *= at(uScale, li(m * uInvPeak));
              ${if (bt2020) "L = mat3(1.6605, -0.1246, -0.0182, -0.5876, 1.1329, -0.1006, -0.0728, -0.0083, 1.1187) * L;" else ""}
              L = min(L, vec3(1.0));
              o = vec4(at(uOetf, li(L.r)), at(uOetf, li(L.g)), at(uOetf, li(L.b)), 1.0);
            }"""
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, if (luts != null) fsTone else fsPlain))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private companion object {
        const val EGL_OPENGL_ES3_BIT = 0x40
        val LUT_NAMES = arrayOf("uLin", "uScale", "uOetf")
    }
}
