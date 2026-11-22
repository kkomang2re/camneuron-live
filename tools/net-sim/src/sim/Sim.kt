package sim

import com.camneuron.core.net.NetGovernor
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
 * 카메라·인코더·치지직 서버·시청자 플레이어는 들어 있지 않다. 방송 세션(LiveSession)이 송출 코드를 엮는 방식을
 * 그대로 따르므로, 그쪽을 바꾸면 여기도 맞춘다.
 *
 * 결과(1초마다 한 줄): 망 속도, 목표 비트레이트, 네트워크 단계, 버린 프레임, 대기열에서 밀린 시간,
 * 서버에 닿은 영상·소리가 가장 늦었던 시간, 건너뛴 영상 길이.
 */

// 앱의 기본 방송: 1080p30 4.5 Mbps, 키프레임 2초, AAC 160 kbps
const val BITRATE = 4_500_000
const val FPS = 30
const val KEY_FRAME_S = 2
const val AUDIO_BPS = 160_000
const val STALL_MS = 10_000L

/** 폰과 서버 사이의 망이 품을 수 있는 양 (중계기의 수신 버퍼로 흉내 낸다). */
const val LINK_BUFFER = 32 * 1024

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

/** 서버에 닿은 것 (1초 단위). */
class Arrivals {
    var videoFrames = 0
    var videoLagMaxMs = 0L
    var videoLagSumMs = 0L
    var audioFrames = 0
    var audioLagMaxMs = 0L
    var lastVideoCaptureNs = 0L
    var maxSkippedMs = 0L // 끝내 오지 않은 영상의 가장 긴 구간

    @Synchronized
    fun video(captureNs: Long, nowNs: Long) {
        val lag = (nowNs - captureNs) / 1_000_000
        videoFrames++
        videoLagMaxMs = maxOf(videoLagMaxMs, lag)
        videoLagSumMs += lag
        if (lastVideoCaptureNs != 0L) maxSkippedMs = maxOf(maxSkippedMs, (captureNs - lastVideoCaptureNs) / 1_000_000)
        lastVideoCaptureNs = captureNs
    }

    @Synchronized
    fun audio(captureNs: Long, nowNs: Long) {
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
            RtmpMessage.VIDEO -> if (p.size >= 26 && p[1].toInt() == 1) arrivals.video(unhex(p, 10), now)
            RtmpMessage.AUDIO -> if (p.size >= 18 && p[1].toInt() == 1) arrivals.audio(unhex(p, 2), now)
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
    val schedule = SCENARIOS[name] ?: error("unknown scenario $name")
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

    val publisher = RtmpPublisher(RtmpUrl("127.0.0.1", proxy.localPort, "live"), "key", RtmpPublisher.sendBufferFor(BITRATE))
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
    val rows = StringBuilder("t,link_kbps,target_kbps,net_level,dropped_total,queue_delay_ms,video_frames,video_lag_max_ms,video_lag_avg_ms,audio_frames,audio_lag_max_ms,skipped_ms\n")
    var stalledAt = -1
    var next = link.startNs
    for (t in 1..link.totalSeconds) {
        next += 1_000_000_000L
        val wait = next - System.nanoTime()
        if (wait > 0) LockSupport.parkNanos(wait)
        val stats = streamer.takeStats()
        if (net.update(stats.drops, stats.fill, System.nanoTime() / 1_000_000)) {
            targetBitrate.set(NetGovernor.limitBitrate(BITRATE, net.level))
            streamer.setSendBuffer(RtmpPublisher.sendBufferFor(targetBitrate.get()))
        }
        val a = arrivals.take()
        rows.append("$t,${link.kbpsAt(System.nanoTime() - 500_000_000)},${targetBitrate.get() / 1000},${net.level},${streamer.droppedFrames},")
            .append("${stats.delayMs},${a[0]},${a[1]},${a[2]},${a[3]},${a[4]},${a[5]}\n")
        if (streamer.isStalled(System.currentTimeMillis(), STALL_MS) || failure[0] != null) {
            stalledAt = t // 앱이라면 여기서 다시 연결한다
            break
        }
    }
    running.set(false)
    out.writeText(rows.toString())
    println("$name: 만든 프레임 ${produced.get()}, 버린 프레임 ${streamer.droppedFrames}, 멈춘 시각 $stalledAt, 실패 ${failure[0]}")
    Runtime.getRuntime().halt(0)
}
