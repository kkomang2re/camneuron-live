package com.camneuron.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.camneuron.core.camera.CameraEngine
import com.camneuron.core.camera.StreamFormat
import com.camneuron.core.device.DeviceStatus
import com.camneuron.core.device.HeatGovernor
import com.camneuron.core.encode.AudioEncoder
import com.camneuron.core.encode.BeautySettings
import com.camneuron.core.encode.BeautyStore
import com.camneuron.core.encode.Codecs
import com.camneuron.core.encode.VideoEncoder
import com.camneuron.core.net.NetGovernor
import com.camneuron.live.chzzk.ChzzkChat
import com.camneuron.live.rtmp.Flv
import com.camneuron.live.rtmp.RtmpException
import com.camneuron.live.rtmp.RtmpPublisher
import com.camneuron.live.rtmp.RtmpStreamer
import com.camneuron.live.rtmp.RtmpUrl
import com.camneuron.live.widget.WidgetCanvas
import com.camneuron.live.widget.WidgetHost
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 폰 카메라와 마이크를 RTMP 서버(치지직)로 바로 보낸다. 방송 하나에 하나, 자기 스레드에서 돈다.
 *
 * 연결이 끊기면 [RETRY_MS]마다 다시 붙는다 (야외에서는 모바일 데이터가 잠깐씩 끊긴다). 단, 처음 연결부터 실패하면
 * 주소나 스트림 키가 틀렸을 가능성이 크므로 다시 시도하지 않고 멈춘다 ([onEnded]에 이유).
 * 카메라를 바꾸거나 미리보기가 붙고 떨어지면 연결은 유지하고 인코더만 새로 만든다 (새 시퀀스 헤더를 보낸다).
 * 발열([HeatGovernor])과 네트워크([NetGovernor])에 따라 비트레이트만 [quality]보다 낮춘다. 방송 중에 해상도를 바꾸면
 * 시청자 플레이어가 멈추는 경우가 있어 아직은 바꾸지 않는다.
 * 마이크 끄기와 화면 가리기는 [LiveControls]를 따라 지금 인코더에 바로 적용한다.
 */
class LiveSession(
    private val context: Context,
    private val engine: CameraEngine,
    private val url: RtmpUrl,
    private val streamKey: String,
    private val quality: LiveQuality,
    private val onEnded: (String?) -> Unit,
) : CameraEngine.Listener, CameraEngine.FacingHandler, AudioEncoder.Sink {
    private val thread = Thread(::run, "live-session")
    private val ticker = Thread(::tickLoop, "live-ticker")
    private val lock = Object()

    @Volatile
    private var stopped = false

    @Volatile
    private var connectionLost: String? = null

    @Volatile
    private var restartRequested = false

    @Volatile
    private var front = engine.controls.front

    @Volatile
    private var streamer: RtmpStreamer? = null

    /** 연결 중인 것. 멈추면 닫아 connect()가 시간 초과를 기다리지 않게 한다. */
    @Volatile
    private var connecting: RtmpPublisher? = null

    @Volatile
    private var encoder: VideoEncoder? = null

    /** 지금 인코더의 크기 (가리기 그림을 이 크기로 그린다). */
    @Volatile
    private var encoderFormat: StreamFormat? = null

    private val controlsListener: () -> Unit = { applyControls() }

    private val main = Handler(Looper.getMainLooper())

    /** 보정 창에서 뷰티 값을 바꾸면 지금 인코더에 바로 적용한다. */
    private val beautyListener: (BeautySettings) -> Unit = { encoder?.setBeauty(it) }

    /** 위젯 그림이 바뀌면 (메인 스레드) 지금 인코더에 얹는다. */
    private val widgetListener = WidgetHost.Listener { index, frame -> pushWidget(encoder, index, frame) }

    private val audioLock = Any()
    private var audio: AudioEncoder? = null // audioLock

    /** 영상 pts가 BOOTTIME 시계인지 (기기마다 다르다). 첫 프레임에서 정한다. 소리 pts(MONOTONIC)를 맞출 때 쓴다. */
    @Volatile
    private var videoClockIsBoottime: Boolean? = null

    private val heat = HeatGovernor()
    private val net = NetGovernor()

    @Volatile
    private var targetBitrate = quality.bitrate

    private val encoderFailures = ArrayDeque<Long>()
    private var lastBytesSent = 0L
    private var lastTickMs = 0L

    fun start() {
        LiveState.update { LiveState.Status(phase = LiveState.Phase.CONNECTING, startedAtMs = SystemClock.elapsedRealtime()) }
        thread.start()
        ticker.start()
    }

    fun stop() {
        stopped = true
        synchronized(lock) { lock.notifyAll() }
        connecting?.close()
        streamer?.stop()
        if (thread !== Thread.currentThread()) thread.join(5000)
        ticker.interrupt()
    }

    private fun run() {
        var everLive = false
        var endReason: String? = null
        engine.addListener(this)
        engine.facingHandler = this
        LiveControls.addListener(controlsListener)
        BeautyStore.init(context)
        BeautyStore.addListener(beautyListener)
        main.post {
            WidgetHost.acquire(context)
            WidgetHost.addListener(widgetListener)
            ChzzkChat.acquire(context) // 앱을 내려도 후원 알림(진동·읽기)이 오게
        }
        Log.i(TAG, "quality ${quality.label} ${quality.bitrateKbps} kbps, key frame every ${quality.keyFrameS}s")
        try {
            while (!stopped) {
                connectionLost = null
                val publisher = RtmpPublisher(url, streamKey) { Log.i(TAG, it) }
                var active: RtmpStreamer? = null
                try {
                    connecting = publisher
                    if (stopped) break
                    publisher.connect()
                    connecting = null
                    active = RtmpStreamer(
                        publisher,
                        queueBytes = quality.bitrate / 8 * QUEUE_SECONDS,
                        onNeedKeyFrame = { encoder?.requestKeyFrame() },
                        onFailed = { lose(it) },
                        log = { Log.i(TAG, it) },
                    )
                    streamer = active
                    lastBytesSent = 0L
                    active.start()
                    everLive = true
                    LiveState.update { it.copy(phase = LiveState.Phase.LIVE, message = null) }
                    stream(active)
                } catch (e: RtmpException) {
                    if (stopped) break
                    if (!everLive) {
                        endReason = e.message
                        break
                    }
                    lose(e.message ?: "연결 실패")
                } catch (e: Exception) {
                    if (stopped) break
                    Log.w(TAG, "stream failed", e)
                    if (!everLive) {
                        endReason = e.message ?: e.javaClass.simpleName
                        break
                    }
                    lose(e.message ?: e.javaClass.simpleName)
                } finally {
                    connecting = null
                    stopAudio()
                    runCatching { engine.detachEncoder() }
                    encoder?.release()
                    encoder = null
                    encoderFormat = null
                    streamer = null
                    active?.stop() ?: publisher.close()
                }
                if (stopped) break
                val reason = connectionLost
                Log.i(TAG, "reconnecting in ${RETRY_MS}ms: $reason")
                LiveState.update { it.copy(phase = LiveState.Phase.RECONNECTING, message = reason, sendKbps = 0) }
                synchronized(lock) { if (!stopped) lock.wait(RETRY_MS) }
            }
        } finally {
            LiveControls.removeListener(controlsListener)
            BeautyStore.removeListener(beautyListener)
            main.post {
                WidgetHost.removeListener(widgetListener)
                WidgetHost.release()
                ChzzkChat.release()
            }
            if (engine.facingHandler === this) engine.facingHandler = null
            engine.removeListener(this)
            LiveState.update { LiveState.Status(message = endReason) }
            if (!stopped) onEnded(endReason)
        }
    }

    /** 연결 하나 동안: 인코더를 만들어 카메라에 붙이고, 끊기거나 멈출 때까지 출력을 보낸다. */
    private fun stream(active: RtmpStreamer) {
        var first = true
        while (!stopped && connectionLost == null) {
            restartRequested = false
            val useFront = front
            val mime = VideoEncoder.MIME_AVC
            val supports = { w: Int, h: Int -> VideoEncoder.findEncoder(mime, w, h) != null }
            val format = engine.resolveFormat(useFront, quality.width, quality.height, quality.fps, supports)
            val name = VideoEncoder.findEncoder(mime, format.width, format.height)
                ?: error("H.264 인코더 없음 (${format.width}x${format.height})")
            val next = VideoEncoder(
                name, mime, format.width, format.height, format.fps, targetBitrate,
                constantBitrate = true, keyFrameIntervalS = quality.keyFrameS,
            )
            try {
                engine.attachEncoder(next.inputSurface, format, useFront)
            } catch (e: Exception) {
                next.release()
                throw e
            }
            encoder?.release()
            encoder = next
            encoderFormat = format
            applyCover(next, format)
            next.setBeauty(BeautyStore.current)
            // 새 인코더에는 위젯 층이 없다. 지금 그림을 다시 얹는다.
            main.post { for (i in 0 until WidgetHost.COUNT) pushWidget(next, i, WidgetHost.latest(i)) }
            LiveState.update { it.copy(format = format, targetKbps = targetBitrate / 1000) }

            if (first) {
                first = false
                val mic = hasMicPermission()
                active.sendMetadata(
                    Flv.metadata(
                        format.width, format.height, format.fps, quality.bitrateKbps,
                        audio = mic, audioSampleRate = AudioEncoder.SAMPLE_RATE, audioChannels = 2, audioKbps = 160,
                        encoder = "CamNeuron Live",
                    ),
                )
                if (mic) startAudio()
            }

            val failedEncoder = try {
                next.drain(
                    { config, keyFrame, pts, data -> onVideo(active, config, keyFrame, pts, data) },
                    keepRunning = { !stopped && connectionLost == null && !restartRequested },
                    onFrame = { _, _ -> },
                )
                false
            } catch (e: IllegalStateException) {
                // 미리보기가 붙거나 떨어질 때 일부 기기(갤럭시 S25의 c2.qti)는 인코더가 오류 상태가 된다. 새로 만든다.
                if (stopped || connectionLost != null || !allowEncoderRecovery()) throw e
                Log.w(TAG, "encoder failed, recreating it", e)
                true
            }
            if (!restartRequested && !failedEncoder) break
        }
    }

    private fun onVideo(active: RtmpStreamer, config: Boolean, keyFrame: Boolean, ptsUs: Long, data: ByteBuffer) {
        val bytes = ByteArray(data.remaining()).also { data.get(it) }
        if (config) {
            active.onVideoConfig(bytes)
            return
        }
        if (videoClockIsBoottime == null) {
            val boottime = SystemClock.elapsedRealtimeNanos() / 1000
            val monotonic = System.nanoTime() / 1000
            videoClockIsBoottime = abs(boottime - ptsUs) < abs(monotonic - ptsUs)
            Log.i(TAG, "video pts clock: ${if (videoClockIsBoottime == true) "BOOTTIME" else "MONOTONIC"}")
        }
        active.onVideoFrame(ptsUs, bytes, keyFrame)
    }

    private fun lose(reason: String) {
        if (connectionLost == null) connectionLost = reason
    }

    // ------------------------------------------------------------------------------------------
    // 소리

    private fun hasMicPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startAudio() = synchronized(audioLock) {
        if (audio == null) {
            audio = AudioEncoder(this, aac = true).also {
                it.muted = LiveControls.audioMuted
                it.start()
            }
        }
    }

    private fun stopAudio() {
        val stopping = synchronized(audioLock) { audio.also { audio = null } }
        stopping?.stop()
    }

    override fun onAudioConfig(codec: Int, sampleRate: Int, channels: Int, codecHeader: ByteArray) {
        if (codec == Codecs.AAC) streamer?.onAudioConfig(codecHeader)
    }

    override fun onAudioPacket(ptsUs: Long, data: ByteBuffer) {
        // 소리 pts는 MONOTONIC이다. 영상 시계로 바꾼다. 영상 시계를 정하기 전의 소리는 버린다.
        val boottime = videoClockIsBoottime ?: return
        val pts = if (boottime) ptsUs + (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) / 1000 else ptsUs
        streamer?.onAudioFrame(pts, data)
    }

    override fun onAudioLevel(peak: Float) = Unit

    override fun onAudioError(encoder: AudioEncoder, message: String) {
        synchronized(audioLock) {
            if (audio !== encoder) return
            audio = null
        }
        Log.w(TAG, "mic stopped: $message")
        LiveState.update { it.copy(micError = message) }
    }

    // ------------------------------------------------------------------------------------------
    // 마이크 끄기 · 화면 가리기

    /** [LiveControls]가 바뀌면 (메인 스레드) 지금 소리 인코더와 영상 인코더에 적용한다. */
    private fun applyControls() {
        synchronized(audioLock) { audio?.muted = LiveControls.audioMuted }
        val current = encoder ?: return
        val format = encoderFormat ?: return
        applyCover(current, format)
    }

    /** 인코더마다 마지막으로 적용한 가림 상태. 마이크만 바뀔 때 다시 그리지 않게 한다. */
    private val coverLock = Any()
    private var coverTarget: VideoEncoder? = null // coverLock
    private var coverApplied = false // coverLock

    /**
     * 가리기를 풀 때는 인코더를 새로 만든다 (연결은 유지, 카메라 전환과 같은 길).
     * 정지 그림을 오래 압축한 인코더는 화질 값이 극단으로 가 있어, 카메라로 돌아오는 순간 1초에 3 MB 가까이 쏟아낸 뒤
     * 2~3초 동안 흐릿해진다 (기기 테스트, 목표 비트레이트를 낮춰도 같았다). 새 인코더가 붙을 때까지는 옛 인코더가 계속 가린다.
     */
    private fun applyCover(target: VideoEncoder, format: StreamFormat) {
        val covered: Boolean
        synchronized(coverLock) {
            covered = LiveControls.covered
            if (target === coverTarget && covered == coverApplied) return
            val uncovering = target === coverTarget && coverApplied && !covered
            if (uncovering) {
                restartRequested = true
                return
            }
            coverTarget = target
            coverApplied = covered
        }
        val bitmap = if (covered) {
            CoverImage.render(
                format.width, format.height,
                context.getString(R.string.cover_title), context.getString(R.string.cover_subtitle),
            )
        } else {
            null
        }
        val ok = target.setCover(bitmap)
        LiveState.update { it.copy(coverUnavailable = !ok && covered) }
    }

    // ------------------------------------------------------------------------------------------
    // 위젯 (채팅창, 후원 알림)

    /** 메인 스레드에서. 방송에 넣기로 한 위젯만 얹고 나머지는 지운다. 그림은 복사해 넘긴다 (인코더가 맡아 해제한다). */
    private fun pushWidget(target: VideoEncoder?, index: Int, frame: WidgetHost.Frame?) {
        if (target == null) return
        val item = frame?.item
        if (frame == null || item == null || !WidgetCanvas.streamed(item)) {
            target.setLayer(index, null, 0f, 0f, 0f, 0f, 0f)
            return
        }
        val copy = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val rect = WidgetCanvas.pixelRect(item)
        val w = WidgetCanvas.WIDTH.toFloat()
        val h = WidgetCanvas.HEIGHT.toFloat()
        target.setLayer(index, copy, rect.left / w, rect.top / h, (rect.left + copy.width) / w, (rect.top + copy.height) / h, 1f)
    }

    // ------------------------------------------------------------------------------------------
    // 카메라

    override fun onFacingRequested(front: Boolean) {
        if (this.front == front) return
        this.front = front
        restartRequested = true
    }

    override fun onEncoderInputReset() {
        Log.i(TAG, "camera reconfigured under the encoder, recreating it")
        restartRequested = true
    }

    private fun allowEncoderRecovery(): Boolean {
        val now = SystemClock.elapsedRealtime()
        encoderFailures.removeAll { now - it > ENCODER_FAILURE_WINDOW_MS }
        encoderFailures.add(now)
        return encoderFailures.size <= MAX_ENCODER_FAILURES
    }

    // ------------------------------------------------------------------------------------------
    // 1초마다: 발열·네트워크 대응, 끊김 감지, 화면 상태

    private fun tickLoop() {
        try {
            while (!stopped) {
                Thread.sleep(TICK_MS)
                tick()
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val device = DeviceStatus.read(context)
        var changed = heat.update(device.thermalStatus, device.temperatureC, now)

        val active = streamer
        var kbps = 0
        if (active != null) {
            val stats = active.takeStats()
            changed = net.update(stats.drops, stats.fill, now) || changed
            val sent = active.bytesSent
            val elapsed = if (lastTickMs > 0) now - lastTickMs else TICK_MS
            if (lastBytesSent > 0) kbps = ((sent - lastBytesSent) * 8 / elapsed.coerceAtLeast(1)).toInt()
            lastBytesSent = sent
            if (active.isStalled(System.currentTimeMillis(), STALL_MS)) {
                lose("네트워크가 멈춤")
                active.stop() // 막힌 쓰기를 풀어 세션이 다시 연결하게 한다
            }
        }
        lastTickMs = now

        if (changed) {
            targetBitrate = NetGovernor.limitBitrate(HeatGovernor.limitBitrate(quality.bitrate, heat.level), net.level)
            Log.i(TAG, "bitrate ${targetBitrate / 1000} kbps (heat ${heat.level}, net ${net.level})")
            encoder?.setBitrate(targetBitrate)
        }
        LiveState.update {
            it.copy(
                sendKbps = kbps, targetKbps = targetBitrate / 1000, heatLevel = heat.level, netLevel = net.level,
                droppedFrames = active?.droppedFrames ?: it.droppedFrames,
            )
        }
    }

    companion object {
        private const val TAG = "LiveSession"

        private const val QUEUE_SECONDS = 2
        private const val RETRY_MS = 3_000L
        private const val TICK_MS = 1_000L
        private const val STALL_MS = 10_000L
        private const val ENCODER_FAILURE_WINDOW_MS = 60_000L
        private const val MAX_ENCODER_FAILURES = 3
    }
}
