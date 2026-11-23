package com.camneuron.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import com.camneuron.live.chzzk.ChzzkLiveStatus
import com.camneuron.live.chzzk.ChzzkPrefs
import com.camneuron.live.chzzk.UrlConnectionTransport
import com.camneuron.live.rtmp.Flv
import com.camneuron.live.rtmp.RtmpException
import com.camneuron.live.rtmp.RtmpPublisher
import com.camneuron.live.rtmp.RtmpStreamer
import com.camneuron.live.rtmp.RtmpUrl
import com.camneuron.live.widget.WidgetCanvas
import com.camneuron.live.widget.WidgetHost
import java.nio.ByteBuffer

/**
 * 폰 카메라와 마이크를 RTMP 서버(치지직)로 바로 보낸다. 방송 하나에 하나, 자기 스레드에서 돈다.
 *
 * 연결이 끊기면 [RETRY_MS]마다 다시 붙는다 (야외에서는 모바일 데이터가 잠깐씩 끊긴다). 단, 처음 연결부터 실패하면
 * 주소나 스트림 키가 틀렸을 가능성이 크므로 다시 시도하지 않고 멈춘다 ([onEnded]에 이유).
 * 카메라를 바꾸거나 미리보기가 붙고 떨어지면 연결은 유지하고 인코더만 새로 만든다. 영상 크기는 처음 정한 대로 지키고,
 * 영상 시각은 인코더마다 다시 맞춘다 ([VideoClock]). 카메라를 바꾸지 못하면 이전 카메라로 돌아가 방송을 잇는다.
 * 네트워크([NetGovernor])가 약하면 비트레이트를 [quality]보다 낮춘다. 폰이 뜨거우면([HeatGovernor]) 처리량을 먼저
 * 줄인다 (60fps면 30fps로, 위젯 그림 뜨기를 드물게, 가장 뜨거울 때만 비트레이트를 조금. [LiveHeat]).
 * 방송 중에 해상도를 바꾸면 시청자 플레이어가 멈추는 경우가 있어 해상도는 바꾸지 않는다.
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

    /** 치지직에 방송이 떴는지와 시청자 수를 [WATCH_MS]마다 읽는다 (네트워크가 느려도 1초 틱을 막지 않게 따로 돈다). */
    private val watcher = Thread(::watchLoop, "live-viewers").apply { isDaemon = true }
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

    /** 영상 시각을 소리와 같은 시계로 옮긴다. 세션 스레드 전용 (인코더 출력을 꺼내는 스레드). */
    private val videoClock = VideoClock()

    /** 이 방송의 영상 크기. 처음 정한 뒤에는 카메라를 바꿔도 지킨다 (방송 중에 크기가 바뀌면 시청자 플레이어가 멈춘다). 세션 스레드 전용. */
    private var streamSize: StreamFormat? = null

    /** 지금 인코더가 붙어 있는 카메라가 전면인지 (세션 스레드 전용). 카메라를 바꾸다 실패하면 이쪽으로 돌아간다. */
    private var attachedFront: Boolean? = null

    /** [LiveState.Status.cameraError]를 지울 시각 (SystemClock.elapsedRealtime). 0이면 보이는 것이 없다. */
    @Volatile
    private var cameraErrorUntilMs = 0L

    private val heat = HeatGovernor()

    private val net = LiveNet.governor()

    @Volatile
    private var targetBitrate = quality.bitrate

    /** 지금 인코더에 요청한 프레임 수 (발열 단계에 따라 [quality]보다 낮을 수 있다). */
    @Volatile
    private var encoderFps = quality.fps

    /** 세션이 끝나 위젯 설정을 되돌렸다. 그 뒤에 늦게 온 틱이 위젯을 다시 느리게 하지 않게. */
    @Volatile
    private var ended = false

    private val encoderFailures = ArrayDeque<Long>()
    private var lastBytesSent = 0L
    private var lastTickMs = 0L

    fun start() {
        LiveState.update { LiveState.Status(phase = LiveState.Phase.CONNECTING, startedAtMs = SystemClock.elapsedRealtime()) }
        thread.start()
        ticker.start()
        watcher.start()
    }

    fun stop() {
        stopped = true
        synchronized(lock) { lock.notifyAll() }
        connecting?.close()
        streamer?.stop()
        if (thread !== Thread.currentThread()) thread.join(5000)
        ticker.interrupt()
        watcher.interrupt()
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
                val publisher = RtmpPublisher(url, streamKey, RtmpPublisher.sendBufferFor(targetBitrate)) { Log.i(TAG, it) }
                var active: RtmpStreamer? = null
                try {
                    connecting = publisher
                    if (stopped) break
                    publisher.connect()
                    connecting = null
                    active = RtmpStreamer(
                        publisher,
                        queueBytes = quality.bitrate / 8 * LiveNet.QUEUE_SECONDS,
                        maxDelayMs = LiveNet.QUEUE_SECONDS * 1000L,
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
            ended = true
            main.post {
                WidgetHost.removeListener(widgetListener)
                WidgetHost.slowdown = 1f
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
            val opened = try {
                openEncoder(useFront)
            } catch (e: Exception) {
                // 카메라를 바꾸다 실패했다 (그 카메라를 열지 못하거나, 방송 크기로 찍지 못하는 폰). 연결을 끊고 다시 붙는 대신
                // 이전 카메라로 돌아가 방송을 잇는다. 돌아가는 것도 실패하면 그때는 예전처럼 끊고 다시 연결한다.
                val previous = attachedFront
                if (stopped || connectionLost != null || previous == null || previous == useFront) throw e
                Log.w(TAG, "camera switch failed, going back to the previous camera", e)
                attachedFront = null
                front = previous
                cameraErrorUntilMs = SystemClock.elapsedRealtime() + CAMERA_ERROR_SHOW_MS
                LiveState.update { it.copy(cameraError = e.message ?: e.javaClass.simpleName) }
                continue
            }
            val next = opened.first
            val format = opened.second
            encoder?.release()
            encoder = next
            encoderFormat = format
            attachedFront = useFront
            if (streamSize == null) streamSize = format
            videoClock.reset() // 카메라가 바뀌었으면 프레임에 찍히는 시각의 기준도 바뀌었을 수 있다
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

    /**
     * [useFront] 카메라에 붙은 새 인코더를 만든다. 이전 인코더는 그때까지 카메라에 붙어 있다 (부른 쪽이 그 뒤에 해제한다).
     * 실패하면 예외를 던진다. 그때 이전 인코더는 더 이상 카메라에서 프레임을 받지 못할 수 있다.
     */
    private fun openEncoder(useFront: Boolean): Pair<VideoEncoder, StreamFormat> {
        val mime = VideoEncoder.MIME_AVC
        val supports = { w: Int, h: Int -> VideoEncoder.findEncoder(mime, w, h) != null }
        val fps = LiveHeat.fps(quality.fps, heat.level)
        encoderFps = fps
        val size = streamSize
        val format = if (size == null) {
            engine.resolveFormat(useFront, quality.width, quality.height, fps, supports)
        } else {
            engine.resolveFormat(useFront, size.width, size.height, fps, supports, keepSize = true)
        }
        val name = VideoEncoder.findEncoder(mime, format.width, format.height)
            ?: error("H.264 인코더 없음 (${format.width}x${format.height})")
        val create = {
            VideoEncoder(
                name, mime, format.width, format.height, format.fps, targetBitrate,
                constantBitrate = true, keyFrameIntervalS = quality.keyFrameS,
            )
        }
        val next = try {
            create()
        } catch (e: Exception) {
            // 같은 크기의 하드웨어 인코더를 둘 띄우지 못하는 폰이 있다. 이전 것을 먼저 놓고 다시 만든다
            // (그동안 영상이 조금 더 길게 빈다).
            val old = encoder ?: throw e
            Log.w(TAG, "could not create a second encoder, releasing the old one first", e)
            runCatching { engine.detachEncoder() }
            old.release()
            encoder = null
            encoderFormat = null
            create()
        }
        try {
            engine.attachEncoder(next.inputSurface, format, useFront)
        } catch (e: Exception) {
            next.release()
            throw e
        }
        return next to format
    }

    private fun onVideo(active: RtmpStreamer, config: Boolean, keyFrame: Boolean, ptsUs: Long, data: ByteBuffer) {
        val bytes = ByteArray(data.remaining()).also { data.get(it) }
        if (config) {
            active.onVideoConfig(bytes)
            return
        }
        val pts = videoClock.toMonotonic(ptsUs, System.nanoTime() / 1000, SystemClock.elapsedRealtimeNanos() / 1000)
        active.onVideoFrame(pts, bytes, keyFrame)
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
            AudioMeter.active = true
        }
    }

    private fun stopAudio() {
        val stopping = synchronized(audioLock) { audio.also { audio = null } }
        AudioMeter.active = false
        stopping?.stop()
    }

    override fun onAudioConfig(codec: Int, sampleRate: Int, channels: Int, codecHeader: ByteArray) {
        if (codec == Codecs.AAC) streamer?.onAudioConfig(codecHeader)
    }

    override fun onAudioPacket(ptsUs: Long, data: ByteBuffer) {
        // 소리 pts는 MONOTONIC이고 영상도 그 시계로 옮겨 보낸다 ([VideoClock]). 첫 영상보다 앞선 소리는 보내는 쪽이 버린다.
        streamer?.onAudioFrame(ptsUs, data)
    }

    override fun onAudioLevel(peak: Float) = AudioMeter.push(peak)

    override fun onAudioError(encoder: AudioEncoder, message: String) {
        synchronized(audioLock) {
            if (audio !== encoder) return
            audio = null
        }
        AudioMeter.active = false
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

    /**
     * 메인 스레드에서. 방송에 넣기로 한 위젯만 얹고 나머지는 지운다.
     * 그림은 복사하지 않고 그대로 넘긴다. [WidgetHost]는 뜰 때마다 새 그림을 만들고 넘긴 그림은 바꾸지도 해제하지도
     * 않는다 (예전에는 그림마다 복사해 초당 수십 장, 장마다 수 MB씩 메모리를 새로 잡았다).
     */
    private fun pushWidget(target: VideoEncoder?, index: Int, frame: WidgetHost.Frame?) {
        if (target == null) return
        val item = frame?.item
        if (frame == null || item == null || !WidgetCanvas.streamed(item)) {
            target.setLayer(index, null, 0f, 0f, 0f, 0f, 0f)
            return
        }
        val bitmap = frame.bitmap
        val rect = WidgetCanvas.pixelRect(item)
        val w = WidgetCanvas.WIDTH.toFloat()
        val h = WidgetCanvas.HEIGHT.toFloat()
        target.setLayer(
            index, bitmap, rect.left / w, rect.top / h, (rect.left + bitmap.width) / w, (rect.top + bitmap.height) / h, 1f,
            owned = false,
        )
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
        val heatChanged = heat.update(device.thermalStatus, device.temperatureC, now)
        var changed = heatChanged

        val active = streamer
        var kbps = 0
        var sendDelayMs = 0L
        if (active != null) {
            val stats = active.takeStats()
            sendDelayMs = stats.delayMs
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
            targetBitrate = NetGovernor.limitBitrate(LiveHeat.bitrate(quality.bitrate, heat.level), net.level)
            Log.i(TAG, "bitrate ${targetBitrate / 1000} kbps (heat ${heat.level}, net ${net.level})")
            encoder?.setBitrate(targetBitrate)
            active?.setSendBuffer(RtmpPublisher.sendBufferFor(targetBitrate))
        }
        if (cameraErrorUntilMs != 0L && now >= cameraErrorUntilMs) {
            cameraErrorUntilMs = 0L
            LiveState.update { it.copy(cameraError = null) }
        }
        if (heatChanged) {
            val level = heat.level
            val fps = LiveHeat.fps(quality.fps, level)
            if (fps != encoderFps && encoder != null) {
                // 연결은 유지하고 인코더만 새 프레임 수로 새로 만든다 (카메라 전환과 같은 길).
                Log.i(TAG, "heat $level: ${encoderFps}fps -> ${fps}fps")
                restartRequested = true
            }
            main.post { if (!ended) WidgetHost.slowdown = LiveHeat.widgetSlowdown(level) }
        }
        LiveState.update {
            it.copy(
                sendKbps = kbps, targetKbps = targetBitrate / 1000, heatLevel = heat.level, netLevel = net.level,
                sendDelayMs = sendDelayMs / 100 * 100, // 화면이 매 틱 바뀌지 않게 0.1초 단위로
                droppedFrames = active?.droppedFrames ?: it.droppedFrames,
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // 치지직에 방송이 떴는지, 시청자 수

    private fun watchLoop() {
        val transport = UrlConnectionTransport(timeoutMs = WATCH_TIMEOUT_MS)
        try {
            Thread.sleep(FIRST_WATCH_MS)
            while (!stopped) {
                val channelId = ChzzkPrefs.channelId(context)
                if (channelId.isNotEmpty()) {
                    val status = ChzzkLiveStatus.fetch(transport, channelId)
                    // 방송이 끝난 뒤에 늦게 온 답은 버린다.
                    if (!stopped) {
                        LiveState.update {
                            if (it.phase == LiveState.Phase.IDLE) {
                                it
                            } else {
                                val viewers = status?.takeIf { s -> s.open }?.viewers ?: -1
                                it.copy(chzzkOpen = status?.open, viewers = viewers, peakViewers = maxOf(it.peakViewers, viewers))
                            }
                        }
                    }
                }
                Thread.sleep(WATCH_MS)
            }
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "LiveSession"

        /** 치지직은 송출을 받고 몇 초 뒤에 방송을 연다. 처음은 조금 기다렸다 읽는다. */
        private const val FIRST_WATCH_MS = 6_000L
        private const val WATCH_MS = 10_000L
        private const val WATCH_TIMEOUT_MS = 8_000


        /** 카메라를 바꾸지 못했다는 줄을 상태에 보이는 시간. */
        private const val CAMERA_ERROR_SHOW_MS = 15_000L
        private const val RETRY_MS = 3_000L
        private const val TICK_MS = 1_000L
        private const val STALL_MS = 10_000L
        private const val ENCODER_FAILURE_WINDOW_MS = 60_000L
        private const val MAX_ENCODER_FAILURES = 3
    }
}
