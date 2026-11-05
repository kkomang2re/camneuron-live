package com.camneuron.core.encode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 카메라 영상을 GPU로 인코더 입력([output])에 옮겨 그리는 단계. 그 사이에 뷰티 보정(프로)과 글자 워터마크
 * (테스터 체험판의 "CamNeuron", [watermark]가 null이면 없음)를 넣는다.
 * 카메라는 [inputSurface]에 그리고, 프레임마다 같은 촬영 시각으로 인코더에 다시 그린다.
 *
 * 카메라 버퍼에 붙은 회전·좌우 반전 표시(transform)는 쓰지 않는다. 카메라가 인코더에 바로 그릴 때는 인코더가 이 표시를
 * 무시하므로, 같은 방향으로 나가게 하려고 GL 좌표에 맞춰 위아래만 뒤집어 그린다.
 *
 * 피부 보정: 줄인 해상도(높이 [MAX_SMOOTH_HEIGHT] 이하)에서 경계를 살리는 흐림(bilateral)을 만들고, 원본 해상도에서
 * 피부색(YCbCr)인 곳만 원본과 차이가 작은 만큼 섞는다. 눈·입·머리카락처럼 경계가 뚜렷한 곳과 피부가 아닌 곳은 거의 그대로다.
 * 밝게: 어두운 곳을 더 많이 올리는 로그 곡선.
 */
class VideoEffects(
    private val output: Surface,
    private val width: Int,
    private val height: Int,
    private val watermark: String?,
) {
    private val thread = HandlerThread("video-effects", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var copyProgram = 0
    private var blurProgram = 0
    private var beautyProgram = 0
    private var markProgram = 0
    private var cameraTex = 0
    private var markTex = 0
    private var coverTex = 0
    private var covered = false

    // 위에 얹는 그림 층 (야외 방송의 채팅창·후원 알림). GL 스레드에서만 쓴다.
    private val layers = arrayOfNulls<Layer>(MAX_LAYERS)
    private var layerProgram = 0
    private var layerAlphaLoc = -1

    private class Layer(val tex: Int, var width: Int, var height: Int, var quad: FloatBuffer, var alpha: Float)
    private var blurStepLoc = -1
    private var smoothAmountLoc = -1
    private var brightLoc = -1
    private val cameraQuad = quad(0f, 0f, width.toFloat(), height.toFloat())
    private var markQuad: FloatBuffer? = null
    private var markWidth = 0
    private var markHeight = 0
    private var drawFailed = false

    // 피부 보정용 줄인 화면. 처음 켤 때 만든다.
    private var smoothFbo = 0
    private var smoothTex = 0
    private var smoothWidth = 0
    private var smoothHeight = 0

    // 0~1. GL 스레드에서만 읽고 쓴다.
    private var smooth = 0f
    private var bright = 0f

    /** 카메라가 그릴 곳. */
    val inputSurface: Surface

    init {
        val setup = FutureTask { setUp() }
        handler.post(setup)
        inputSurface = try {
            setup.get(SETUP_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            release()
            throw e.cause ?: e
        }
    }

    /** 피부 보정([smooth])과 밝게([bright]), 각각 0~1. 둘 다 0이면 그대로 옮겨 그린다. */
    fun setBeauty(smooth: Float, bright: Float) {
        handler.post {
            this.smooth = smooth.coerceIn(0f, 1f)
            this.bright = bright.coerceIn(0f, 1f)
        }
    }

    /**
     * OBS가 영상을 좌우([flipH])·상하([flipV])로 뒤집어 보여 주면, 워터마크를 미리 반대쪽 모서리에 뒤집어 그려
     * 방송 화면에서는 오른쪽 아래에 바로 읽히게 한다.
     */
    fun setFlip(flipH: Boolean, flipV: Boolean) {
        handler.post { if (markQuad != null) markQuad = placedMark(flipH, flipV) }
    }

    /**
     * [bitmap]을 주면 카메라 대신 그 그림을 화면 가득 보낸다 (화면 가리기). null이면 다시 카메라.
     * 카메라 프레임이 올 때마다 그리므로 송출 프레임 수는 그대로다. [bitmap]은 이 함수가 맡아 다 쓰면 recycle한다.
     */
    fun setCover(bitmap: Bitmap?) {
        handler.post {
            if (bitmap == null) {
                covered = false
                return@post
            }
            try {
                if (markProgram == 0) markProgram = program(FRAGMENT_MARK)
                if (coverTex == 0) coverTex = newTexture(GLES20.GL_TEXTURE_2D) else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, coverTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                covered = true
            } catch (e: Exception) {
                Log.w(TAG, "failed to set cover", e)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * [index]번 층에 [bitmap]을 영상 좌표 0~1의 사각형([left]~[right], [top]~[bottom])으로 얹는다. [alpha]는 0~1.
     * 카메라(또는 가리기 그림) 위, 워터마크 아래에 번호 순서대로 그린다. [bitmap]이 null이면 그 층을 지운다.
     * [bitmap]은 알파를 미리 곱한 ARGB_8888이어야 하며, 이 함수가 맡아 올린 뒤 recycle한다.
     */
    fun setLayer(index: Int, bitmap: Bitmap?, left: Float, top: Float, right: Float, bottom: Float, alpha: Float) {
        require(index in 0 until MAX_LAYERS) { "layer $index" }
        val posted = handler.post {
            try {
                val current = layers[index]
                if (bitmap == null) {
                    if (current != null) GLES20.glDeleteTextures(1, intArrayOf(current.tex), 0)
                    layers[index] = null
                    return@post
                }
                if (layerProgram == 0) {
                    layerProgram = program(FRAGMENT_LAYER)
                    layerAlphaLoc = GLES20.glGetUniformLocation(layerProgram, "uAlpha")
                }
                val quad = quad(left * width, top * height, right * width, bottom * height)
                if (current != null && current.width == bitmap.width && current.height == bitmap.height) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, current.tex)
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
                    current.quad = quad
                    current.alpha = alpha
                } else {
                    val tex = current?.tex ?: newTexture(GLES20.GL_TEXTURE_2D)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    layers[index] = Layer(tex, bitmap.width, bitmap.height, quad, alpha)
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to set layer $index", e)
            } finally {
                bitmap?.recycle()
            }
        }
        if (!posted) bitmap?.recycle() // 이미 해제됨
    }

    /** 그리기를 멈추고 GPU 자원을 놓는다. 인코더를 해제하기 전에 부른다. */
    fun release() {
        handler.post(::tearDown)
        thread.quitSafely()
        thread.join(RELEASE_TIMEOUT_MS)
    }

    private fun setUp(): Surface {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1, // 인코더 입력에 그릴 수 있는 설정
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no recordable EGL config" }
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

        copyProgram = program(FRAGMENT_COPY)
        blurProgram = program(FRAGMENT_BLUR)
        blurStepLoc = GLES20.glGetUniformLocation(blurProgram, "uStep")
        beautyProgram = program(FRAGMENT_BEAUTY)
        smoothAmountLoc = GLES20.glGetUniformLocation(beautyProgram, "uSmoothAmount")
        brightLoc = GLES20.glGetUniformLocation(beautyProgram, "uBright")
        GLES20.glUseProgram(beautyProgram)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(beautyProgram, "uSmooth"), 1) // 줄인 화면은 텍스처 1번
        cameraTex = newTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)

        if (watermark != null) {
            markProgram = program(FRAGMENT_MARK)
            markTex = newTexture(GLES20.GL_TEXTURE_2D)
            val bitmap = renderText(watermark)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            markWidth = bitmap.width
            markHeight = bitmap.height
            markQuad = placedMark(flipH = false, flipV = false)
            bitmap.recycle()
        }

        val camera = SurfaceTexture(cameraTex).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({ draw() }, handler)
        }
        texture = camera
        return Surface(camera).also { cameraSurface = it }
    }

    private fun draw() {
        val camera = texture ?: return
        try {
            camera.updateTexImage()
            if (smooth > 0f && !covered) renderSmoothed()
            GLES20.glViewport(0, 0, width, height)
            if (covered) {
                drawQuad(markProgram, GLES20.GL_TEXTURE_2D, coverTex, cameraQuad)
            } else if (smooth > 0f || bright > 0f) {
                GLES20.glUseProgram(beautyProgram)
                GLES20.glUniform1f(smoothAmountLoc, smooth)
                GLES20.glUniform1f(brightLoc, bright)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, smoothTex)
                drawQuad(beautyProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex, cameraQuad)
            } else {
                drawQuad(copyProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex, cameraQuad)
            }
            drawLayers()
            markQuad?.let { quad ->
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // Bitmap은 알파를 미리 곱해 올라간다
                drawQuad(markProgram, GLES20.GL_TEXTURE_2D, markTex, quad)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
            // 인코더 pts는 카메라가 바로 그릴 때와 같은 촬영 시각 (지연 측정과 소리 맞춤에 쓰임)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, camera.timestamp)
            check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
        } catch (e: Exception) {
            // 인코더를 바꾸는 중 등. 같은 오류가 프레임마다 쌓이지 않게 한 번만 남긴다.
            if (!drawFailed) Log.w(TAG, "draw failed", e)
            drawFailed = true
        }
    }

    private fun drawLayers() {
        if (layers.all { it == null }) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // Bitmap은 알파를 미리 곱해 올라간다
        for (layer in layers) {
            if (layer == null || layer.alpha <= 0f) continue
            GLES20.glUseProgram(layerProgram)
            GLES20.glUniform1f(layerAlphaLoc, layer.alpha.coerceIn(0f, 1f))
            drawQuad(layerProgram, GLES20.GL_TEXTURE_2D, layer.tex, layer.quad)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** 경계를 살린 흐림을 줄인 화면에 그린다. 텍스처의 위쪽 줄이 t=1이 된다 (GL 프레임버퍼는 아래가 0). */
    private fun renderSmoothed() {
        if (smoothFbo == 0) createSmoothTarget()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smoothFbo)
        GLES20.glViewport(0, 0, smoothWidth, smoothHeight)
        GLES20.glUseProgram(blurProgram)
        // 원본 높이에 비례한 반경: 1080p에서 첫 고리 약 5px, 둘째 고리 약 11px
        val radius = height * BLUR_RADIUS
        GLES20.glUniform2f(blurStepLoc, radius / width, radius / height)
        drawQuad(blurProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex, cameraQuad)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun createSmoothTarget() {
        smoothHeight = minOf(height / 2, MAX_SMOOTH_HEIGHT)
        smoothWidth = (width.toLong() * smoothHeight / height).toInt()
        smoothTex = newTexture(GLES20.GL_TEXTURE_2D)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, smoothWidth, smoothHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        smoothFbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smoothFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, smoothTex, 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "smoothing framebuffer incomplete: 0x${Integer.toHexString(status)}" }
        Log.i(TAG, "skin smoothing at ${smoothWidth}x$smoothHeight for ${width}x$height")
    }

    private fun tearDown() {
        texture?.setOnFrameAvailableListener(null)
        cameraSurface?.release()
        texture?.release()
        texture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT) {
                GLES20.glDeleteTextures(4, intArrayOf(cameraTex, markTex, smoothTex, coverTex), 0)
                for (layer in layers) if (layer != null) GLES20.glDeleteTextures(1, intArrayOf(layer.tex), 0)
                if (layerProgram != 0) GLES20.glDeleteProgram(layerProgram)
                if (smoothFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(smoothFbo), 0)
                for (p in intArrayOf(copyProgram, blurProgram, beautyProgram, markProgram)) GLES20.glDeleteProgram(p)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            // eglTerminate는 부르지 않는다: 같은 프로세스의 화면 그리기(미리보기)와 기본 디스플레이를 함께 쓴다.
            EGL14.eglReleaseThread()
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    /** 흰 굵은 글자와 반투명한 어두운 둥근 바탕. 크기는 영상 높이에 비례한다. */
    private fun renderText(text: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = height * TEXT_SIZE
            color = Color.WHITE
            alpha = TEXT_ALPHA
        }
        val metrics = paint.fontMetrics
        val padX = paint.textSize * 0.5f
        val padY = paint.textSize * 0.2f
        val w = ceil(paint.measureText(text) + padX * 2).toInt()
        val h = ceil(metrics.descent - metrics.ascent + padY * 2).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(BACKGROUND_ALPHA, 0, 0, 0) }
        Canvas(bitmap).apply {
            drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, background)
            drawText(text, padX, padY - metrics.ascent, paint)
        }
        return bitmap
    }

    /** 오른쪽 아래 자리. 뒤집기는 꼭짓점 위치만 거울처럼 옮겨, 자리와 글자 방향이 함께 뒤집히게 한다. */
    private fun placedMark(flipH: Boolean, flipV: Boolean): FloatBuffer {
        val margin = height * MARGIN
        return quad(width - margin - markWidth, height - margin - markHeight, width - margin, height - margin, flipH, flipV)
    }

    /** 영상 좌표(px, 위쪽이 0)의 사각형을 GL 꼭짓점(x, y, s, t)으로. 텍스처는 위쪽 줄이 t=0이다. */
    private fun quad(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        flipH: Boolean = false,
        flipV: Boolean = false,
    ): FloatBuffer {
        val sx = if (flipH) -1 else 1
        val sy = if (flipV) -1 else 1
        val l = (left / width * 2 - 1) * sx
        val r = (right / width * 2 - 1) * sx
        val t = (1 - top / height * 2) * sy
        val b = (1 - bottom / height * 2) * sy
        val values = floatArrayOf(l, b, 0f, 1f, r, b, 1f, 1f, l, t, 0f, 0f, r, t, 1f, 0f)
        return ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }
    }

    private fun drawQuad(program: Int, target: Int, tex: Int, quad: FloatBuffer) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        quad.position(0)
        GLES20.glVertexAttribPointer(ATTR_POS, 2, GLES20.GL_FLOAT, false, STRIDE, quad)
        GLES20.glEnableVertexAttribArray(ATTR_POS)
        quad.position(2)
        GLES20.glVertexAttribPointer(ATTR_TEX, 2, GLES20.GL_FLOAT, false, STRIDE, quad)
        GLES20.glEnableVertexAttribArray(ATTR_TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun program(fragment: String): Int {
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glBindAttribLocation(program, ATTR_POS, "aPos")
        GLES20.glBindAttribLocation(program, ATTR_TEX, "aTex")
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun newTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    companion object {
        private const val TAG = "VideoEffects"
        private const val SETUP_TIMEOUT_S = 5L
        private const val RELEASE_TIMEOUT_MS = 2000L

        /** 글자 크기와 가장자리 여백 (영상 높이에 대한 비율). 1080p에서 약 32px 글자. */
        private const val TEXT_SIZE = 0.03f
        private const val MARGIN = 0.03f
        private const val TEXT_ALPHA = 230
        private const val BACKGROUND_ALPHA = 100

        /** 피부 보정 흐림의 고리 간격 (영상 높이에 대한 비율)과 줄인 화면의 최대 높이. */
        private const val BLUR_RADIUS = 0.005f
        private const val MAX_SMOOTH_HEIGHT = 720

        /** 흐림에서 색 차이가 이만큼이면 무게가 약 60%로 준다 (RGB 0~1 거리). 클수록 경계도 흐려진다. */
        private const val BLUR_COLOR_SIGMA = 0.1

        private const val ATTR_POS = 0
        private const val ATTR_TEX = 1
        private const val STRIDE = 4 * 4

        private const val VERTEX = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = aTex;
            }
        """

        // 4K에서 mediump 텍스처 좌표는 몇 픽셀씩 어긋날 수 있어 되면 highp를 쓴다.
        private const val PRECISION = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
        """
        private const val OES = "#extension GL_OES_EGL_image_external : require\n"

        private const val FRAGMENT_COPY = OES + PRECISION + """
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
        """

        /** 두 고리(8방향씩) 16점과 가운데. 색이 비슷한 점만 섞여 경계가 살아 있다. 반복문 없이 펼쳐 둔다. */
        private val FRAGMENT_BLUR: String = buildString {
            append(OES).append(PRECISION)
            append("varying vec2 vTex;\nuniform samplerExternalOES uTex;\nuniform vec2 uStep;\n")
            append("void main() {\n  vec3 c = texture2D(uTex, vTex).rgb;\n  vec3 sum = c;\n  float total = 1.0;\n")
            append("  vec3 s;\n  vec3 d;\n  float w;\n")
            val falloff = -1.0 / (2.0 * BLUR_COLOR_SIGMA * BLUR_COLOR_SIGMA)
            for (ring in 1..2) {
                val spatial = if (ring == 1) 0.8 else 0.45
                for (i in 0 until 8) {
                    val angle = i * PI / 4 + if (ring == 2) PI / 8 else 0.0
                    append(
                        String.format(
                            Locale.ROOT,
                            "  s = texture2D(uTex, vTex + uStep * vec2(%.4f, %.4f)).rgb; d = s - c;" +
                                " w = %.2f * exp(dot(d, d) * %.1f); sum += s * w; total += w;\n",
                            cos(angle) * ring, sin(angle) * ring, spatial, falloff,
                        ),
                    )
                }
            }
            append("  gl_FragColor = vec4(sum / total, 1.0);\n}\n")
        }

        /**
         * 피부색(BT.601 YCbCr에서 Cb 77~127, Cr 133~173 근처)이고 흐린 값과 차이가 작은 곳만 흐린 쪽으로 섞은 뒤 밝게 한다.
         * 줄인 화면은 위아래가 반대로 담겨 있어 t를 뒤집어 읽는다.
         */
        private const val FRAGMENT_BEAUTY = OES + PRECISION + """
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            uniform sampler2D uSmooth;
            uniform float uSmoothAmount;
            uniform float uBright;
            void main() {
                vec3 c = texture2D(uTex, vTex).rgb;
                vec3 s = texture2D(uSmooth, vec2(vTex.x, 1.0 - vTex.y)).rgb;
                float cb = 0.5 + dot(s, vec3(-0.168736, -0.331264, 0.5));
                float cr = 0.5 + dot(s, vec3(0.5, -0.418688, -0.081312));
                float skin = smoothstep(0.28, 0.32, cb) * (1.0 - smoothstep(0.48, 0.52, cb))
                           * smoothstep(0.50, 0.54, cr) * (1.0 - smoothstep(0.66, 0.70, cr));
                float keep = smoothstep(0.06, 0.20, length(c - s));
                vec3 col = mix(c, s, uSmoothAmount * skin * (1.0 - keep));
                if (uBright > 0.0) {
                    float b = 1.0 + uBright * 3.0;
                    col = log(col * (b - 1.0) + 1.0) / log(b);
                }
                gl_FragColor = vec4(col, 1.0);
            }
        """

        const val MAX_LAYERS = 4

        /** 알파를 미리 곱한 그림을 [uAlpha]만큼 투명하게. */
        private const val FRAGMENT_LAYER = PRECISION + """
            varying vec2 vTex;
            uniform sampler2D uTex;
            uniform float uAlpha;
            void main() {
                gl_FragColor = texture2D(uTex, vTex) * uAlpha;
            }
        """

        private const val FRAGMENT_MARK = PRECISION + """
            varying vec2 vTex;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
        """
    }
}
