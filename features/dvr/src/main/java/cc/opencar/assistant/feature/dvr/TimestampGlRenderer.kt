package cc.opencar.assistant.feature.dvr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Export-only GLES pass: draws a decoded frame (external OES texture) full
 * screen onto an encoder Surface and burns `yyyy-MM-dd HH:mm:ss` into the
 * top-right corner. All calls must come from the thread that called [init].
 */
class TimestampGlRenderer(
    private val width: Int,
    private val height: Int,
    timeZone: TimeZone = TimeZone.getDefault(),
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var programOes = 0
    private var program2d = 0
    private var aPosOes = 0
    private var aTexOes = 0
    private var uTexMatrix = 0
    private var uSamplerOes = 0
    private var aPos2d = 0
    private var aTex2d = 0
    private var uSampler2d = 0
    private var oesTex = 0
    private var overlayTex = 0
    private val texMatrix = FloatArray(16)
    private var quad: FloatBuffer? = null
    private var overlayBmp: Bitmap? = null
    private var overlayCanvas: Canvas? = null
    private var lastStampSec = Long.MIN_VALUE
    private var overlayW = 1
    private var overlayH = 1

    private var frameThread: HandlerThread? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private val frameLock = Object()
    private var frameAvailable = false

    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).also { it.timeZone = timeZone }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val bgPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }

    /** Surface the decoder renders into; valid after [init]. */
    fun decoderSurface(): Surface? = decoderSurface

    fun init(encoderSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize" }
        val attrib = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attrib, 0, configs, 0, 1, num, 0) && num[0] > 0) { "eglChooseConfig" }
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }

        programOes = buildProgram(VERT_OES, FRAG_OES)
        aPosOes = GLES20.glGetAttribLocation(programOes, "aPosition")
        aTexOes = GLES20.glGetAttribLocation(programOes, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(programOes, "uTexMatrix")
        uSamplerOes = GLES20.glGetUniformLocation(programOes, "sTexture")
        program2d = buildProgram(VERT_2D, FRAG_2D)
        aPos2d = GLES20.glGetAttribLocation(program2d, "aPosition")
        aTex2d = GLES20.glGetAttribLocation(program2d, "aTexCoord")
        uSampler2d = GLES20.glGetUniformLocation(program2d, "sTexture")

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        oesTex = ids[0]
        overlayTex = ids[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        texParams(GLES20.GL_TEXTURE_2D)

        textPaint.textSize = (height / 24f).coerceIn(14f, 40f)
        val pad = (textPaint.textSize * 0.35f).toInt().coerceAtLeast(4)
        overlayW = (textPaint.measureText("0000-00-00 00:00:00") + pad * 2).toInt().coerceAtLeast(8)
        overlayH = (textPaint.textSize + pad * 2).toInt().coerceAtLeast(8)
        overlayBmp = Bitmap.createBitmap(overlayW, overlayH, Bitmap.Config.ARGB_8888)
        overlayCanvas = Canvas(overlayBmp!!)
        quad = floatBufferOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        val ft = HandlerThread("oaa-burn-frames").also { it.start() }
        frameThread = ft
        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({
            synchronized(frameLock) {
                frameAvailable = true
                frameLock.notifyAll()
            }
        }, Handler(ft.looper))
        surfaceTexture = st
        decoderSurface = Surface(st)
    }

    /** Wait for the decoder's next rendered frame and latch it into the OES texture. */
    fun awaitFrame(timeoutMs: Long = FRAME_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(frameLock) {
            while (!frameAvailable) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                frameLock.wait(left)
            }
            frameAvailable = false
        }
        surfaceTexture?.updateTexImage()
        return true
    }

    /** Draw the latched frame plus [wallMs] as text, stamped with [presentationNs]. */
    fun drawFrame(wallMs: Long, presentationNs: Long) {
        val q = quad ?: return
        val st = surfaceTexture ?: return
        st.getTransformMatrix(texMatrix)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programOes)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(uSamplerOes, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        bindQuad(q, aPosOes, aTexOes)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        drawStamp(q, wallMs)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationNs)
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            "eglSwapBuffers err=0x${Integer.toHexString(EGL14.eglGetError())}"
        }
    }

    private fun drawStamp(q: FloatBuffer, wallMs: Long) {
        val bmp = overlayBmp ?: return
        val canvas = overlayCanvas ?: return
        val sec = Math.floorDiv(wallMs, 1000L)
        if (sec != lastStampSec) {
            lastStampSec = sec
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawRect(0f, 0f, overlayW.toFloat(), overlayH.toFloat(), bgPaint)
            val pad = textPaint.textSize * 0.35f
            canvas.drawText(stampFmt.format(Date(sec * 1000L)), pad, overlayH - pad - textPaint.descent(), textPaint)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        }
        val margin = (height * 0.015f).toInt().coerceAtLeast(6)
        val x = (width - overlayW - margin).coerceAtLeast(0)
        val yGl = (height - overlayH - margin).coerceAtLeast(0)
        GLES20.glViewport(x, yGl, overlayW, overlayH)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program2d)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        GLES20.glUniform1i(uSampler2d, 0)
        bindQuad(q, aPos2d, aTex2d)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        runCatching { decoderSurface?.release() }
        runCatching { surfaceTexture?.release() }
        decoderSurface = null
        surfaceTexture = null
        frameThread?.quitSafely()
        frameThread = null
        runCatching {
            if (programOes != 0) GLES20.glDeleteProgram(programOes)
            if (program2d != 0) GLES20.glDeleteProgram(program2d)
            if (oesTex != 0 || overlayTex != 0) GLES20.glDeleteTextures(2, intArrayOf(oesTex, overlayTex), 0)
        }
        programOes = 0
        program2d = 0
        oesTex = 0
        overlayTex = 0
        runCatching {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        overlayBmp?.recycle()
        overlayBmp = null
        overlayCanvas = null
    }

    private fun bindQuad(q: FloatBuffer, aPos: Int, aTex: Int) {
        q.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, q)
        q.position(2)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, q)
    }

    private fun texParams(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(vert: String, frag: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vert)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, frag)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val link = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0)
        check(link[0] != 0) { "link: ${GLES20.glGetProgramInfoLog(prog)}" }
        return prog
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(values)
        fb.position(0)
        return fb
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val FRAME_TIMEOUT_MS = 2_500L
        private const val VERT_OES = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aPosition;
              vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        private const val FRAG_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
              gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
        private const val VERT_2D = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aPosition;
              // Bitmap row 0 is the top; GLUtils uploads it at v=0, so flip for upright text.
              vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            }
        """
        private const val FRAG_2D = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
              gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
