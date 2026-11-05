package com.camneuron.core.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 카메라 하나를 폰 화면 미리보기와 인코더(송출)에 함께 연결한다.
 *
 * 연결된 Surface가 바뀔 때마다 캡처 세션을 다시 만들고, 둘 다 없으면 카메라를 닫는다.
 * 모든 카메라 작업은 전용 스레드(executor)에서 순서대로 처리하고, Camera2 콜백은 별도 스레드에서 받아
 * executor가 콜백을 기다리며 막혀도 교착되지 않게 한다.
 */
class CameraEngine private constructor(context: Context) {
    interface Listener {
        fun onCapsChanged(caps: CameraCaps) {}
        fun onControlsChanged(controls: CameraControls) {}
        fun onPreviewSizeChanged(size: Size) {}
        fun onError(message: String?) {}

        /**
         * 인코더가 붙은 채로 카메라 세션을 다시 만들었다 (미리보기를 붙이거나 뗄 때, 카메라를 다시 열 때).
         * 일부 기기(갤럭시 S25의 c2.qti 인코더)는 이렇게 입력이 다시 연결되면 인코더가 멈추거나 오류가 나므로,
         * 송출 세션은 인코더를 새로 만들어 다시 붙인다.
         */
        fun onEncoderInputReset() {}
    }

    /** 송출 중에는 카메라를 바꿀 때 인코더도 새 카메라에 맞춰 다시 만들어야 하므로 세션이 처리한다. */
    fun interface FacingHandler {
        fun onFacingRequested(front: Boolean)
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val callbackThread = HandlerThread("camera-callbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "camera-engine") }
    private val listeners = CopyOnWriteArraySet<Listener>()

    // 아래 필드는 executor 스레드에서만 바꾼다.
    private var device: CameraDevice? = null
    private var characteristics: CameraCharacteristics? = null
    private var session: CameraCaptureSession? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null
    private var encoderFormat: StreamFormat? = null
    private var retryScheduled = false

    /** 카메라가 이전 미리보기에 더 이상 그리지 않게 된 뒤(다음 세션 재구성 뒤)에 할 정리. */
    private val pendingRelease = mutableListOf<() -> Unit>()

    @Volatile
    var controls = CameraControls()
        private set

    @Volatile
    var caps: CameraCaps? = null
        private set

    @Volatile
    var previewSize: Size? = null
        private set

    @Volatile
    var facingHandler: FacingHandler? = null

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * 화면 미리보기를 연결하거나(null이면) 뗀다. [afterDetach]는 카메라가 이전 SurfaceTexture를
     * 더 이상 쓰지 않게 된 뒤에 불린다 (TextureView가 사라질 때 그 시점에 해제하려고).
     */
    fun setPreviewTexture(texture: SurfaceTexture?, afterDetach: (() -> Unit)? = null) = executor.execute {
        if (texture === previewTexture) {
            afterDetach?.invoke()
            return@execute
        }
        val oldSurface = previewSurface
        previewSurface = null
        previewTexture = texture
        if (encoderSurface == null) {
            reconfigureOrRetry()
            oldSurface?.release()
            afterDetach?.invoke()
        } else {
            // 송출 중에는 지금 인코더 입력으로 세션을 다시 만들지 않는다 (일부 기기에서 인코더가 멈추거나 요청이 거부됨).
            // 송출 세션이 인코더를 새로 만들어 붙일 때 미리보기도 함께 반영되고, 그때까지 카메라는 이전 미리보기에
            // 계속 그리므로 이전 Surface 해제는 그 뒤로 미룬다.
            pendingRelease += { oldSurface?.release(); afterDetach?.invoke() }
            listeners.forEach { it.onEncoderInputReset() }
        }
    }

    /** 인코더를 연결하고 카메라가 그리기 시작할 때까지 기다린다. 실패하면 예외를 던진다. */
    fun attachEncoder(surface: Surface, format: StreamFormat, front: Boolean) {
        val future = executor.submit<Unit> {
            encoderSurface = surface
            encoderFormat = format
            if (controls.front != front) setControls(controls.copy(front = front))
            val error = reconfigure()
            if (error != null) {
                encoderSurface = null
                encoderFormat = null
                reconfigureOrRetry()
                throw error
            }
        }
        try {
            future.get(ATTACH_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** 인코더를 뗀다. 미리보기가 있으면 카메라는 계속 켜 둔다. */
    fun detachEncoder() {
        executor.submit<Unit> {
            encoderSurface = null
            encoderFormat = null
            reconfigureOrRetry()
        }.get(ATTACH_TIMEOUT_S, TimeUnit.SECONDS)
    }

    /** 카메라 값 변경. 전면/후면 전환은 [requestFacing]으로 한다. */
    fun update(transform: (CameraControls) -> CameraControls) = executor.execute {
        val requested = transform(controls).copy(front = controls.front)
        val next = caps?.let { requested.clampedTo(it) } ?: requested
        if (next == controls) return@execute
        val refocus = next.focusMode == FocusMode.AUTO && controls.focusMode != FocusMode.AUTO
        setControls(next)
        applyRequest(triggerAutoFocus = refocus)
    }

    /** 화면의 한 점에 초점과 노출을 맞추고 그 상태로 고정한다. */
    fun focusAt(point: NormPoint) = executor.execute {
        setControls(controls.copy(focusMode = FocusMode.AUTO, focusPoint = point, aeLock = false))
        applyRequest(triggerAutoFocus = true)
    }

    fun requestFacing(front: Boolean) {
        val handler = facingHandler
        if (handler != null) {
            handler.onFacingRequested(front)
            return
        }
        executor.execute {
            if (controls.front == front) return@execute
            setControls(controls.copy(front = front))
            reconfigureOrRetry()
        }
    }

    /** 요청과 가장 가까우면서 카메라와 인코더([encoderSupports])가 모두 지원하는 형식을 고른다. */
    fun resolveFormat(front: Boolean, width: Int, height: Int, fps: Int, encoderSupports: (Int, Int) -> Boolean): StreamFormat {
        val id = cameraIdFor(front) ?: throw IOException("no camera")
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IOException("camera $id has no stream configuration")

        val sizes = map.getOutputSizes(MediaCodec::class.java).filter { encoderSupports(it.width, it.height) }
        if (sizes.isEmpty()) throw IOException("no usable video size")
        // 요청한 fps를 낼 수 있는 크기를 우선한다.
        val fastEnough = sizes.filter {
            val minDuration = map.getOutputMinFrameDuration(MediaCodec::class.java, it)
            minDuration == 0L || minDuration <= 1_000_000_000L / fps
        }
        val size = pickSize(fastEnough.ifEmpty { sizes }, width, height)
        return StreamFormat(size.width, size.height, pickFpsRange(chars, fps).upper)
    }

    // ------------------------------------------------------------------------------------------
    // executor 스레드 전용

    private fun setControls(next: CameraControls) {
        controls = next
        listeners.forEach { it.onControlsChanged(next) }
    }

    /** 인코더를 붙이는 경우([attachEncoder])를 뺀 모든 세션 재구성. */
    private fun reconfigureOrRetry() {
        if (reconfigure() != null) {
            scheduleRetry()
        } else if (encoderSurface != null) {
            listeners.forEach { it.onEncoderInputReset() }
        }
    }

    /** 지금 연결된 Surface로 카메라 세션을 다시 만든다. 실패하면 그 예외를 돌려준다. */
    private fun reconfigure(): Exception? = try {
        reconfigureSession()
    } finally {
        // 이전 세션은 닫혔으므로 (새 세션 구성은 이전 세션이 끝난 뒤 완료됨) 미뤄 둔 미리보기 정리를 한다.
        pendingRelease.forEach { it() }
        pendingRelease.clear()
    }

    private fun reconfigureSession(): Exception? {
        closeSession()
        if (previewTexture == null && encoderSurface == null) {
            closeDevice()
            return null
        }
        return try {
            val id = cameraIdFor(controls.front) ?: throw IOException("no camera")
            if (device?.id != id) {
                closeDevice()
                openDevice(id)
            }
            val camera = device ?: throw IOException("camera not open")
            preparePreviewSurface()
            session = createSession(camera, listOfNotNull(previewSurface, encoderSurface))
            applyRequest(triggerAutoFocus = controls.focusMode == FocusMode.AUTO)
            null
        } catch (e: Exception) {
            Log.w(TAG, "camera setup failed", e)
            closeDevice()
            listeners.forEach { it.onError(e.message ?: e.javaClass.simpleName) }
            e
        }
    }

    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        callbackHandler.postDelayed({
            executor.execute {
                retryScheduled = false
                if (device == null) reconfigureOrRetry()
            }
        }, RETRY_DELAY_MS)
    }

    @SuppressLint("MissingPermission") // 카메라 권한을 받은 뒤에만 엔진을 쓴다.
    private fun openDevice(id: String) {
        val opened = CompletableFuture<CameraDevice>()
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!opened.complete(camera)) camera.close() // 이미 시간 초과로 포기한 경우
            }

            override fun onDisconnected(camera: CameraDevice) = lost(camera, "camera disconnected")

            override fun onError(camera: CameraDevice, error: Int) = lost(camera, "camera error $error")

            private fun lost(camera: CameraDevice, reason: String) {
                camera.close()
                if (!opened.completeExceptionally(IOException(reason))) {
                    executor.execute { onDeviceLost(camera, reason) }
                }
            }
        }, callbackHandler)

        val camera = try {
            opened.get(TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause as? Exception ?: e
        } catch (e: Exception) {
            opened.cancel(false)
            throw e
        }
        val chars = manager.getCameraCharacteristics(id)
        device = camera
        characteristics = chars

        val newCaps = buildCaps(chars)
        caps = newCaps
        // 다른 카메라로 바뀌면 화면 좌표가 달라지므로 탭 초점은 연속 자동으로 되돌린다.
        val focusMode = if (controls.focusMode == FocusMode.AUTO) FocusMode.CONTINUOUS else controls.focusMode
        setControls(controls.copy(focusMode = focusMode, focusPoint = null).clampedTo(newCaps))
        listeners.forEach { it.onCapsChanged(newCaps) }
        Log.i(TAG, "opened camera $id: $newCaps")
    }

    private fun onDeviceLost(camera: CameraDevice, reason: String) {
        if (device !== camera) return
        Log.w(TAG, reason)
        session = null
        device = null
        characteristics = null
        listeners.forEach { it.onError(reason) }
        scheduleRetry()
    }

    @Suppress("DEPRECATION") // SessionConfiguration은 API 28부터라 minSdk 26에서는 이 방식을 쓴다.
    private fun createSession(camera: CameraDevice, surfaces: List<Surface>): CameraCaptureSession {
        val configured = CompletableFuture<CameraCaptureSession>()
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                configured.complete(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                configured.completeExceptionally(IOException("capture session configuration failed"))
            }
        }, callbackHandler)
        try {
            return configured.get(TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause as? Exception ?: e
        }
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
    }

    private fun closeDevice() {
        closeSession()
        runCatching { device?.close() }
        device = null
        characteristics = null
    }

    /** 송출 영상과 같은 화면비의 미리보기 크기를 골라 SurfaceTexture에 맞춘다. */
    private fun preparePreviewSurface() {
        val texture = previewTexture ?: return
        val chars = characteristics ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val aspect = encoderFormat?.let { it.width.toFloat() / it.height } ?: DEFAULT_ASPECT
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)
        val size = sizes.filter { abs(it.width.toFloat() / it.height - aspect) < 0.01f && it.width <= MAX_PREVIEW_WIDTH }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minBy { abs(it.width.toFloat() / it.height - aspect) }
        texture.setDefaultBufferSize(size.width, size.height)
        if (previewSurface == null) previewSurface = Surface(texture)
        if (size != previewSize) {
            previewSize = size
            listeners.forEach { it.onPreviewSizeChanged(size) }
        }
    }

    private fun applyRequest(triggerAutoFocus: Boolean) {
        val camera = device ?: return
        val captureSession = session ?: return
        val chars = characteristics ?: return
        val c = controls
        try {
            val template = if (encoderSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = camera.createCaptureRequest(template)
            previewSurface?.let(builder::addTarget)
            encoderSurface?.let(builder::addTarget)

            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pickFpsRange(chars, encoderFormat?.fps ?: PREVIEW_FPS))
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            applyZoom(builder, chars, c.zoom)
            applyFocus(builder, chars, c)

            val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
            val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                (c.ev / evStep).roundToInt().coerceIn(evRange.lower, evRange.upper),
            )
            builder.set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, c.awbLock)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, c.whiteBalance.awbMode)
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (c.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
            )
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (c.stabilization) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )

            captureSession.setRepeatingRequest(builder.build(), null, callbackHandler)
            if (triggerAutoFocus && c.focusMode == FocusMode.AUTO) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                captureSession.capture(builder.build(), null, callbackHandler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                captureSession.capture(builder.build(), null, callbackHandler)
            }
        } catch (e: Exception) {
            // 세션이 닫히는 중에 들어온 요청 등. 다음 reconfigure가 다시 적용한다.
            Log.w(TAG, "failed to apply capture request", e)
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder, chars: CameraCharacteristics, zoom: Float) {
        if (supportsZoomRatio(chars)) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(chars, zoom))
        }
    }

    private fun applyFocus(builder: CaptureRequest.Builder, chars: CameraCharacteristics, c: CameraControls) {
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        when (c.focusMode) {
            FocusMode.CONTINUOUS -> builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                when {
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    else -> CameraMetadata.CONTROL_AF_MODE_OFF
                },
            )

            FocusMode.AUTO -> {
                val region = meteringRegion(chars, c.focusPoint ?: NormPoint(0.5f, 0.5f), c.zoom)
                if (CameraMetadata.CONTROL_AF_MODE_AUTO in afModes) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                }
                if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                    builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                }
                if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
            }

            FocusMode.MANUAL -> {
                val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDistance.coerceIn(0f, 1f) * minFocus)
            }
        }
    }

    /**
     * 송출 영상 좌표(0~1)를 센서 좌표의 측광 영역으로 바꾼다.
     * 송출 화면비(16:9)는 센서(보통 4:3) 가운데를 잘라 낸 것이라 그만큼 보정한다.
     */
    private fun meteringRegion(chars: CameraCharacteristics, point: NormPoint, zoom: Float): MeteringRectangle {
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        // 줌 배율 API를 쓰면 측광 좌표가 확대된 화면 기준이 되고, 아니면 잘라 낸 영역 기준이다.
        val base = if (supportsZoomRatio(chars)) Rect(0, 0, active.width(), active.height()) else cropRegion(chars, zoom)
        val aspect = encoderFormat?.let { it.width.toFloat() / it.height } ?: DEFAULT_ASPECT
        var width = base.width().toFloat()
        var height = base.height().toFloat()
        if (width / height > aspect) width = height * aspect else height = width / aspect

        val x = base.left + (base.width() - width) / 2 + point.x.coerceIn(0f, 1f) * width
        val y = base.top + (base.height() - height) / 2 + point.y.coerceIn(0f, 1f) * height
        val half = min(width, height) * REGION_HALF_SIZE
        val rect = Rect(
            (x - half).toInt().coerceAtLeast(base.left),
            (y - half).toInt().coerceAtLeast(base.top),
            (x + half).toInt().coerceAtMost(base.right - 1),
            (y + half).toInt().coerceAtMost(base.bottom - 1),
        )
        return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    private fun buildCaps(chars: CameraCharacteristics): CameraCaps {
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }
        val maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val stabilizationModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
        val facings = manager.cameraIdList.map { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }

        return CameraCaps(
            front = front,
            hasFront = CameraMetadata.LENS_FACING_FRONT in facings,
            hasBack = CameraMetadata.LENS_FACING_BACK in facings,
            zoomMin = zoomRange?.lower ?: 1f,
            zoomMax = zoomRange?.upper ?: maxDigitalZoom,
            evMin = evRange.lower * evStep,
            evMax = evRange.upper * evStep,
            evStep = evStep,
            manualFocus = minFocus > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes,
            torch = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            stabilization = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in stabilizationModes,
            whiteBalances = WhiteBalance.entries.filter { it.awbMode in awbModes },
        )
    }

    private fun cameraIdFor(front: Boolean): String? {
        val facing = if (front) CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK
        val ids = manager.cameraIdList
        return ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
            ?: ids.firstOrNull()
    }

    companion object {
        private const val TAG = "CameraEngine"
        private const val TIMEOUT_S = 5L
        private const val ATTACH_TIMEOUT_S = 15L
        private const val RETRY_DELAY_MS = 2000L
        private const val PREVIEW_FPS = 30
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val DEFAULT_ASPECT = 16f / 9f
        private const val REGION_HALF_SIZE = 0.08f

        @Volatile
        private var instance: CameraEngine? = null

        fun get(context: Context): CameraEngine =
            instance ?: synchronized(this) {
                instance ?: CameraEngine(context.applicationContext).also { instance = it }
            }

        private fun supportsZoomRatio(chars: CameraCharacteristics): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null

        /** 줌 배율 API가 없는 기기용: 센서 가운데를 잘라 확대한다. */
        private fun cropRegion(chars: CameraCharacteristics, zoom: Float): Rect {
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            val factor = zoom.coerceAtLeast(1f)
            val width = (active.width() / factor).toInt()
            val height = (active.height() / factor).toInt()
            val left = (active.width() - width) / 2
            val top = (active.height() - height) / 2
            return Rect(left, top, left + width, top + height)
        }

        fun pickSize(sizes: List<Size>, width: Int, height: Int): Size {
            sizes.firstOrNull { it.width == width && it.height == height }?.let { return it }
            // 같은 화면비 중 요청보다 크지 않은 가장 큰 크기, 없으면 전체 중에서 같은 기준.
            val sameAspect = sizes.filter { abs(it.width * height - it.height * width) <= width }
            val pool = sameAspect.ifEmpty { sizes }
            val requestedArea = width.toLong() * height
            return pool.filter { it.width.toLong() * it.height <= requestedArea }.maxByOrNull { it.width.toLong() * it.height }
                ?: pool.minBy { it.width.toLong() * it.height }
        }

        fun pickFpsRange(chars: CameraCharacteristics, fps: Int): Range<Int> {
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().toList()
            return pickFpsRange(ranges, fps)
        }

        fun pickFpsRange(ranges: List<Range<Int>>, fps: Int): Range<Int> {
            if (ranges.isEmpty()) return Range(fps, fps)
            // 고정 fps가 가장 좋고, 없으면 상한이 맞는 것 중 하한이 높은 것.
            ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
            ranges.filter { it.upper == fps }.maxByOrNull { it.lower }?.let { return it }
            return ranges.filter { it.upper <= fps }.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
                ?: ranges.minBy { it.upper }
        }
    }
}
