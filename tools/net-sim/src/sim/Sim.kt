package sim

import com.camneuron.live.LiveNet
import com.camneuron.live.rtmp.Amf0
import com.camneuron.live.rtmp.ChunkReader
import com.camneuron.live.rtmp.ChunkWriter
import com.camneuron.live.rtmp.Flv
import com.camneuron.live.rtmp.RtmpMessage
import com.camneuron.live.rtmp.RtmpPublisher
import com.camneuron.live.rtmp.RtmpStreamer
import com.camneuron.live.rtmp.RtmpUrl
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/*
 * 방송 지연 측정: 앱의 실제 송출 코드를 가짜 RTMP 서버에 연결하고, 그 사이에 속도가 바뀌는 망을 둔다.
 * 프레임마다 찍은 시각을 넣어 보내고 서버에 닿은 시각과 비교한다 (폰 안에서 밀린 시간 + 망).
 * 카메라·인코더·치지직 서버는 들어 있지 않고, 시청자 플레이어는 단순한 모형이다 ([Viewer]). 방송 세션(LiveSession)이
 * 송출 코드를 엮는 방식을 그대로 따르므로, 그쪽을 바꾸면 여기도 맞춘다.
 *
 * 결과(1초마다 한 줄): 망 속도, 목표 비트레이트, 네트워크 단계, 버린 프레임, 대기열에서 밀린 시간,
 * 서버에 닿은 영상·소리가 가장 늦었던 시간, 건너뛴 영상 길이, 시청자 지연과 화면이 멈춘 횟수(모형), 건너뛴 시간.
 */

// 앱의 기본 방송: 1080p30 4.5 Mbps, 키프레임 2초, AAC 160 kbps
const val BITRATE = 4_500_000
const val FPS = 30
const val KEY_FRAME_S = 2
const val AUDIO_BPS = 160_000
const val STALL_MS = 10_000L

/**
 * 중계기(망)의 수신 버퍼. 작게 둔다: 실제 망에서는 서버가 받기 전의 것은 보낸 쪽 소켓 버퍼에 남아 있는 것으로 치므로,
 * 망이 따로 품는 양은 거의 없다. 크게 두면 (전에는 32KB) 이 PC의 내부 연결이 그 절반씩 덩어리로 넘겨, 보내는 쪽에서
 * 재는 망 속도가 실제보다 훨씬 들쭉날쭉해진다.
 */
const val LINK_BUFFER = 4 * 1024

/** 시간에 따른 망 속도: (초, kbit/s). */
val SCENARIOS: Map<String, List<Pair<Int, Int>>> = mapOf(
    "good" to listOf(60 to 20_000),
    "dip" to listOf(15 to 20_000, 30 to 2_000, 30 to 20_000),
    "weak" to listOf(15 to 20_000, 40 to 1_200, 25 to 20_000),
    "blackout" to listOf(15 to 20_000, 6 to 0, 30 to 20_000),
    "flap" to listOf(10 to 20_000) + List(8) { listOf(5 to 1_000, 5 to 20_000) }.flatten(),
    // 자주 나빠지는 망: 느려짐, 4초 끊김, 최저 비트레이트보다 느림이 번갈아 온다.
    "mixed" to List(3) {
        listOf(30 to 20_000, 10 to 1_800, 15 to 20_000, 4 to 0, 20 to 20_000, 12 to 1_000)
    }.flatten() + listOf(25 to 20_000),
    // 걸으며 방송: 45초 좋고 6초 느리다.
    "walk" to List(5) { listOf(45 to 20_000, 6 to 1_500) }.flatten() + listOf(30 to 20_000),
    // 아주 느린 망 (엘리베이터, 사람 많은 곳, 폰이 뜨거워 통신이 느려질 때): 소리(160 kbps)조차 다 못 보낸다.
    "trickle" to listOf(15 to 20_000, 60 to 100, 30 to 20_000),
    // 소리는 보낼 수 있지만 영상은 못 보내는 망.
    "crawl" to listOf(15 to 20_000, 60 to 300, 30 to 20_000),
    // 소리를 겨우 보낼 만한 망이 3분 동안 이어진다.
    "barely" to listOf(15 to 20_000, 180 to 200, 30 to 20_000),
    // 8초씩 완전히 끊겼다 돌아오기를 되풀이한다 (다시 연결하는 기준인 10초보다 짧다).
    "outages" to listOf(15 to 20_000) + List(6) { listOf(8 to 0, 7 to 20_000) }.flatten() + listOf(20 to 20_000),
    // 한참 동안 느린 망 (600 kbps).
    "slow" to listOf(15 to 20_000, 120 to 600, 20 to 20_000),
    "soak" to List(5) {
        listOf(30 to 20_000, 10 to 1_800, 15 to 20_000, 4 to 0, 20 to 20_000, 12 to 1_000)
    }.flatten() + listOf(25 to 20_000),
)

class Link(private val schedule: List<Pair<Int, Int>>) {
    @Volatile
    var startNs = 0L
    val totalSeconds = schedule.sumOf { it.first }

    fun kbpsAt(ns: Long): Int {
        if (startNs == 0L) return schedule.first().second
        var t = (ns - startNs) / 1e9
        for ((seconds, kbps) in schedule) {
            if (t < seconds) return kbps
            t -= seconds
        }
        return schedule.last().second
    }
}

/**
 * 시청자 플레이어 모형: 따라잡지 않는 플레이어 (치지직 모바일 앱에서 지연이 늘어난 채 돌아오지 않는 것을 흉내 낸다).
 *
 * 서버에 닿은 것을 방송 시각(RTMP 타임스탬프) 순서대로 1배속으로 튼다. 처음에는 [START_BUFFER_MS]만큼 모은 뒤 틀기 시작하고,
 * 받아 둔 것이 떨어지면 멈췄다가 [REBUFFER_MS]만큼 다시 모이면 그 자리에서 이어 튼다 (건너뛰거나 빨리 감지 않는다).
 * 서버·CDN을 거치는 시간은 [PIPELINE_MS]로 고정했다. 지연 = 지금 − 지금 보이는 장면을 찍은 시각. 망이 좋을 때는 5초다.
 *
 * **영상과 소리가 둘 다 닿은 데까지만 틀 수 있다.** 서버는 영상과 소리를 한 조각(세그먼트)으로 묶어 내보내고 조각은 영상
 * 프레임이 있어야 끝나므로, 영상 없이 소리만 닿는 동안에는 시청자에게 아무것도 나가지 않다가 영상이 다시 닿는 순간 그동안의
 * 소리까지 한꺼번에 나간다. 그래서 폰이 소리만 보내며 영상을 쉰 시간은 고스란히 시청자 지연이 된다.
 * (처음 모형은 소리만 닿아도 트는 것으로 보아 이것을 놓쳤다: 영상을 20초까지 쉬던 0.0.11~0.0.13에서 실제 지연이 28초까지 갔다.)
 *
 * 폰이 늦게 보낸 만큼 방송 시각이 한꺼번에 몰려 닿으면 플레이어에 그만큼 쌓여 지연이 늘어난다. 폰이 밀린 것을 영상·소리 함께
 * 버리고 방송 시각을 이어 붙이면 몰려 닿는 것이 없어 늘어나지 않는다. 실제 플레이어·서버가 이대로 움직이는지는 확인하지 못했다.
 */
class Viewer {
    // 받았지만 아직 틀지 않은 것: (방송 시각 ms, 찍은 시각 ns)
    private val pendingVideo = ArrayDeque<LongArray>()
    private val pendingAudio = ArrayDeque<LongArray>()
    private var videoEdgeMs = -1L
    private var audioEdgeMs = -1L
    private var playheadMs = 0.0
    private var lastNs = 0L
    private var startNs = 0L
    private var shownCaptureNs = 0L
    private var started = false
    private var stalled = false

    /** 멈춘 횟수 (받아 둔 것이 떨어진 횟수). */
    var stalls = 0
        private set

    @Synchronized
    fun arrive(mediaMs: Long, captureNs: Long, nowNs: Long, video: Boolean) {
        advance(nowNs)
        if (!started) {
            started = true
            startNs = nowNs + START_BUFFER_MS * 1_000_000
            playheadMs = mediaMs.toDouble()
            shownCaptureNs = captureNs
        }
        if (video) {
            videoEdgeMs = maxOf(videoEdgeMs, mediaMs)
            pendingVideo.addLast(longArrayOf(mediaMs, captureNs))
        } else {
            audioEdgeMs = maxOf(audioEdgeMs, mediaMs)
            pendingAudio.addLast(longArrayOf(mediaMs, captureNs))
        }
        advance(nowNs)
    }

    /** 지금의 시청자 지연 (ms). 아직 아무것도 못 받았으면 -1. */
    @Synchronized
    fun delayMs(nowNs: Long): Long {
        if (!started) return -1
        advance(nowNs)
        if (nowNs < startNs) return START_BUFFER_MS + PIPELINE_MS
        return (nowNs - shownCaptureNs) / 1_000_000 + PIPELINE_MS
    }

    private fun advance(nowNs: Long) {
        val edge = minOf(videoEdgeMs, audioEdgeMs) // 둘 다 닿은 데까지
        if (started && edge >= 0 && nowNs > startNs) {
            if (stalled && edge - playheadMs >= REBUFFER_MS) {
                stalled = false
                lastNs = nowNs
            }
            if (!stalled) {
                val target = playheadMs + (nowNs - maxOf(lastNs, startNs)) / 1e6
                if (target >= edge) {
                    playheadMs = maxOf(playheadMs, edge.toDouble())
                    stalled = true
                    stalls++
                } else {
                    playheadMs = target
                }
                for (pending in listOf(pendingVideo, pendingAudio)) {
                    while (pending.isNotEmpty() && pending.first()[0] <= playheadMs) {
                        shownCaptureNs = maxOf(shownCaptureNs, pending.removeFirst()[1])
                    }
                }
            }
        }
        lastNs = nowNs
    }

    companion object {
        const val START_BUFFER_MS = 3_000L
        const val REBUFFER_MS = 1_000L
        const val PIPELINE_MS = 2_000L
    }
}

/** 서버에 닿은 것 (1초 단위). */
class Arrivals {
    val viewer = Viewer()

    var videoFrames = 0
    var videoLagMaxMs = 0L
    var videoLagSumMs = 0L
    var audioFrames = 0
    var audioLagMaxMs = 0L
    var lastVideoCaptureNs = 0L
    var maxSkippedMs = 0L // 끝내 오지 않은 영상의 가장 긴 구간

    @Synchronized
    fun video(captureNs: Long, nowNs: Long, mediaMs: Long) {
        viewer.arrive(mediaMs, captureNs, nowNs, video = true)
        val lag = (nowNs - captureNs) / 1_000_000
        videoFrames++
        videoLagMaxMs = maxOf(videoLagMaxMs, lag)
        videoLagSumMs += lag
        if (lastVideoCaptureNs != 0L) maxSkippedMs = maxOf(maxSkippedMs, (captureNs - lastVideoCaptureNs) / 1_000_000)
        lastVideoCaptureNs = captureNs
    }

    @Synchronized
    fun audio(captureNs: Long, nowNs: Long, mediaMs: Long) {
        viewer.arrive(mediaMs, captureNs, nowNs, video = false)
        audioFrames++
        audioLagMaxMs = maxOf(audioLagMaxMs, (nowNs - captureNs) / 1_000_000)
    }

    /** 이번 1초의 값을 돌려주고 다음 1초를 시작한다. */
    @Synchronized
    fun take(): IntArray {
        val avg = if (videoFrames > 0) videoLagSumMs / videoFrames else -1
        val row = intArrayOf(videoFrames, videoLagMaxMs.toInt(), avg.toInt(), audioFrames, audioLagMaxMs.toInt(), maxSkippedMs.toInt())
        videoFrames = 0; videoLagMaxMs = 0; videoLagSumMs = 0; audioFrames = 0; audioLagMaxMs = 0; maxSkippedMs = 0
        return row
    }
}

fun hex(ns: Long): ByteArray = String.format("%016x", ns).toByteArray(Charsets.US_ASCII)

fun unhex(data: ByteArray, offset: Int): Long = String(data, offset, 16, Charsets.US_ASCII).toLong(16)

/** 찍은 시각을 담은 Annex-B NAL 하나. 나머지는 시작 코드가 생기지 않는 채움 바이트다. */
fun videoFrame(key: Boolean, captureNs: Long, size: Int): ByteArray {
    val out = ByteArray(maxOf(size, 4 + 1 + 16)) { 0x55 }
    out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
    out[4] = if (key) 0x65 else 0x41
    hex(captureNs).copyInto(out, 5)
    return out
}

val VIDEO_CONFIG: ByteArray = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xAC.toByte(), 0x2B, 0x40, 0x3C) +
    byteArrayOf(0, 0, 0, 1, 0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())

/** 서버 쪽: RTMP 핸드셰이크와 publish에 답한 뒤, 프레임이 닿을 때마다 얼마나 늦었는지 적는다. */
fun serve(socket: Socket, arrivals: Arrivals) {
    val input = DataInputStream(socket.getInputStream().buffered())
    val out = BufferedOutputStream(socket.getOutputStream())
    check(input.readUnsignedByte() == 3)
    val c1 = ByteArray(1536).also { input.readFully(it) }
    val s1 = ByteArray(1536) { (it * 31).toByte() }
    out.write(3); out.write(s1); out.write(c1); out.flush()
    input.readFully(ByteArray(1536))

    val reader = ChunkReader(input)
    val writer = ChunkWriter(out)
    fun reply(streamId: Int, vararg values: Any?) {
        writer.write(3, RtmpMessage(RtmpMessage.COMMAND_AMF0, streamId, 0, Amf0.encode(*values)))
        writer.flush()
    }
    while (true) {
        val message = reader.readMessage()
        val now = System.nanoTime()
        val p = message.payload
        when (message.type) {
            RtmpMessage.VIDEO -> if (p.size >= 26 && p[1].toInt() == 1) arrivals.video(unhex(p, 10), now, message.timestamp)
            RtmpMessage.AUDIO -> if (p.size >= 18 && p[1].toInt() == 1) arrivals.audio(unhex(p, 2), now, message.timestamp)
            RtmpMessage.COMMAND_AMF0 -> {
                val values = Amf0.decodeAll(p)
                val txn = values[1]
                when (values[0]) {
                    "connect" -> {
                        writer.write(2, RtmpMessage(RtmpMessage.WINDOW_ACK_SIZE, 0, 0, RtmpMessage.int32(2_500_000)))
                        writer.write(2, RtmpMessage(RtmpMessage.SET_PEER_BANDWIDTH, 0, 0, RtmpMessage.int32(2_500_000) + byteArrayOf(2)))
                        reply(0, "_result", txn, mapOf("fmsVer" to "FMS/3,0,1,123"), mapOf("code" to "NetConnection.Connect.Success"))
                    }
                    "createStream" -> reply(0, "_result", txn, null, 1.0)
                    "publish" -> reply(1, "onStatus", 0, null, mapOf("level" to "status", "code" to "NetStream.Publish.Start"))
                }
            }
        }
    }
}

/** 망: 폰 → 서버는 정해진 속도까지만 넘긴다. 서버 → 폰은 제한하지 않는다. */
fun pump(client: Socket, server: Socket, link: Link) {
    Thread({
        runCatching {
            val buf = ByteArray(16 * 1024)
            val input = server.getInputStream()
            val output = client.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n); output.flush()
            }
        }
    }, "link-down").apply { isDaemon = true; start() }

    val buf = ByteArray(8 * 1024)
    val input = client.getInputStream()
    val output = server.getOutputStream()
    client.soTimeout = 20
    var tokens = 0.0
    var last = System.nanoTime()
    while (true) {
        val now = System.nanoTime()
        val bytesPerS = link.kbpsAt(now) * 1000 / 8.0
        tokens = minOf(tokens + bytesPerS * (now - last) / 1e9, maxOf(bytesPerS * 0.02, 1500.0)) // 몰아 보내기는 20ms 분량까지
        last = now
        if (bytesPerS <= 0.0) { tokens = 0.0; Thread.sleep(5); continue }
        if (tokens < 1.0) { LockSupport.parkNanos(1_000_000); continue }
        val n = try {
            input.read(buf, 0, minOf(buf.size, tokens.toInt()))
        } catch (_: SocketTimeoutException) {
            0
        }
        if (n < 0) break
        if (n > 0) {
            output.write(buf, 0, n); output.flush()
            tokens -= n
        }
    }
}

fun main(args: Array<String>) {
    val name = args[0]
    // 이름 대신 "초x속도,초x속도,..."(kbit/s)로 직접 줄 수도 있다: 15x20000,150x150,30x20000
    val schedule = SCENARIOS[name] ?: runCatching {
        name.split(',').map { part -> part.split('x').let { it[0].trim().toInt() to it[1].trim().toInt() } }
    }.getOrNull() ?: error("unknown scenario $name")
    val out = File(args[1])
    val link = Link(schedule)
    val arrivals = Arrivals()

    val sink = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
    val proxy = ServerSocket()
    proxy.receiveBufferSize = LINK_BUFFER // 받아들인 소켓이 물려받는다
    proxy.bind(InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0))
    Thread({ runCatching { sink.accept().use { serve(it, arrivals) } } }, "server").apply { isDaemon = true; start() }
    Thread({
        runCatching {
            val client = proxy.accept()
            val server = Socket(java.net.InetAddress.getLoopbackAddress(), sink.localPort)
            server.tcpNoDelay = true
            pump(client, server, link)
        }
    }, "link-up").apply { isDaemon = true; start() }

    var sendBufferBytes = RtmpPublisher.sendBufferFor(BITRATE + LiveNet.AUDIO_BPS)
    val publisher = RtmpPublisher(RtmpUrl("127.0.0.1", proxy.localPort, "live"), "key", sendBufferBytes)
    publisher.connect()

    val keyRequested = AtomicBoolean(false)
    val failure = arrayOfNulls<String>(1)
    val targetBitrate = AtomicInteger(BITRATE)
    val produced = AtomicInteger(0)
    val running = AtomicBoolean(true)
    val streamer = RtmpStreamer(
        publisher,
        queueBytes = BITRATE / 8 * LiveNet.QUEUE_SECONDS,
        maxDelayMs = LiveNet.QUEUE_SECONDS * 1000L,
        onNeedKeyFrame = { keyRequested.set(true) },
        onFailed = { failure[0] = it },
    )
    streamer.start()
    streamer.sendMetadata(Flv.metadata(1920, 1080, FPS, BITRATE / 1000, true, 48_000, 2, AUDIO_BPS / 1000, "sim"))
    link.startNs = System.nanoTime()

    // 인코더: 지금 목표 비트레이트로 고르게, 키프레임은 2초마다 또는 요청받았을 때.
    val video = Thread({
        streamer.onVideoConfig(VIDEO_CONFIG)
        val period = 1_000_000_000L / FPS
        val gop = FPS * KEY_FRAME_S
        var sinceKey = gop
        var next = System.nanoTime()
        while (running.get()) {
            val wait = next - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
            val capture = System.nanoTime()
            val key = keyRequested.getAndSet(false) || sinceKey >= gop
            if (key) sinceKey = 0
            val average = targetBitrate.get() / 8 / FPS
            val size = if (key) average * 5 else maxOf(200, (average * gop - average * 5) / (gop - 1))
            streamer.onVideoFrame(capture / 1000, videoFrame(key, capture, size), key)
            produced.incrementAndGet()
            sinceKey++
            next += period
        }
    }, "encoder")
    // 마이크: 1024샘플마다 AAC 프레임 하나.
    val audio = Thread({
        streamer.onAudioConfig(byteArrayOf(0x11, 0x90.toByte()))
        val period = 1_000_000_000L * 1024 / 48_000
        val size = AUDIO_BPS / 8 * 1024 / 48_000
        var next = System.nanoTime()
        while (running.get()) {
            val wait = next - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
            val capture = System.nanoTime()
            val frame = ByteArray(size) { 0x55 }
            hex(capture).copyInto(frame, 0)
            streamer.onAudioFrame(capture / 1000, ByteBuffer.wrap(frame))
            next += period
        }
    }, "mic")
    video.start(); audio.start()

    // LiveSession의 1초 틱: 네트워크 대응 → 인코더 비트레이트와 소켓 버퍼, 멈춤 확인.
    val net = LiveNet.governor()
    val debug = System.getenv("SIM_DEBUG") != null // 단계가 바뀌거나 건너뛸 때마다 한 줄씩 찍는다
    val rows = StringBuilder("t,link_kbps,target_kbps,net_level,dropped_total,queue_delay_ms,video_frames,video_lag_max_ms,video_lag_avg_ms,audio_frames,audio_lag_max_ms,skipped_ms,viewer_delay_ms,viewer_stalls,skipped_total_ms\n")
    var stalledAt = -1
    var audioSkippedMs = 0L
    var next = link.startNs
    for (t in 1..link.totalSeconds) {
        // 0.125초마다: 밀리기 시작하면 바로 망 속도에 맞는 비트레이트로 내린다 (LiveSession.fastTick).
        repeat(8) {
            next += 125_000_000L
            val wait = next - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
            val delayNow = streamer.queueDelayMs()
            val linkBps = streamer.linkBps(1_500)
            if (net.onDelay(delayNow, linkBps, System.nanoTime() / 1_000_000) { LiveNet.totalBitrate(BITRATE, it) }) {
                targetBitrate.set(LiveNet.limitBitrate(BITRATE, net.level))
                if (debug) println("  %.2fs: 밀림 %dms, 잰 망 속도 %d kbps → %d단계 (%d kbps)".format((System.nanoTime() - link.startNs) / 1e9, delayNow, linkBps / 1000, net.level, targetBitrate.get() / 1000))
            }
        }
        val stats = streamer.takeStats()
        if (net.update(stats.drops, stats.fill, System.nanoTime() / 1_000_000)) {
            targetBitrate.set(LiveNet.limitBitrate(BITRATE, net.level))
        }
        val bytes = LiveNet.sendBuffer(sendBufferBytes, targetBitrate.get() + LiveNet.AUDIO_BPS, stats.delayMs, stats.drops > 0)
        if (bytes != sendBufferBytes) {
            sendBufferBytes = bytes
            streamer.setSendBuffer(bytes)
        }
        audioSkippedMs += stats.skippedMs
        if (debug && stats.drops > 0) println("  %ds: 건너뜀 %d번 (%dms), %d단계".format(t, stats.drops, stats.skippedMs, net.level))
        val a = arrivals.take()
        rows.append("$t,${link.kbpsAt(System.nanoTime() - 500_000_000)},${targetBitrate.get() / 1000},${net.level},${streamer.droppedFrames},")
            .append("${stats.delayMs},${a[0]},${a[1]},${a[2]},${a[3]},${a[4]},${a[5]},${arrivals.viewer.delayMs(System.nanoTime())},${arrivals.viewer.stalls},$audioSkippedMs\n")
        if (streamer.isStalled(System.currentTimeMillis(), STALL_MS) || failure[0] != null) {
            stalledAt = t // 앱이라면 여기서 다시 연결한다
            break
        }
    }
    running.set(false)
    out.writeText(rows.toString())
    println("$name: 만든 프레임 ${produced.get()}, 버린 프레임 ${streamer.droppedFrames}, 건너뛴 시간 ${audioSkippedMs}ms, 멈춘 시각 $stalledAt, 실패 ${failure[0]}")
    Runtime.getRuntime().halt(0)
}
