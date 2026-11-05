package com.camneuron.live.rtmp

import java.io.IOException
import java.nio.ByteBuffer

/**
 * 인코더 출력을 RTMP 메시지로 바꿔 대기열에 넣고, 송신 스레드가 서버로 보낸다. 연결 하나에 하나씩 만든다.
 *
 * 시각: 영상과 소리의 pts(µs)는 같은 시계여야 한다 (세션이 맞춘다). 첫 영상 프레임을 0ms로 삼고,
 * 그 전의 소리는 버린다. 각 트랙의 시각은 줄어들지 않게 맞춘다 (서버가 거꾸로 가는 시각을 거부한다).
 */
class RtmpStreamer(
    private val publisher: RtmpPublisher,
    queueBytes: Int,
    onNeedKeyFrame: () -> Unit,
    private val onFailed: (String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val queue = MediaQueue(queueBytes, onNeedKeyFrame)
    private val thread = Thread(::sendLoop, "rtmp-sender")

    @Volatile
    private var running = false

    @Volatile
    private var failed = false

    // 영상 출력 스레드 전용
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var sequenceHeaderSent = false
    private var lastVideoMs = 0L

    // 소리 스레드 전용
    private var lastAudioMs = -1L

    @Volatile
    private var baseUs = NONE

    /** 지금 쓰기 하나가 시작된 시각 (System.currentTimeMillis). 쓰지 않는 중이면 0. */
    @Volatile
    private var writeStartedMs = 0L

    val droppedFrames: Int get() = queue.droppedFrames
    val bytesSent: Long get() = publisher.bytesSent

    fun start() {
        running = true
        publisher.startReader { fail(it) }
        thread.start()
    }

    fun stop() {
        running = false
        queue.close()
        publisher.close()
        if (thread !== Thread.currentThread()) thread.join(2000)
    }

    fun takeStats(): MediaQueue.Stats = queue.takeStats()

    /** 쓰기 하나가 [limitMs]보다 오래 막혀 있으면 true (네트워크가 사실상 끊김). */
    fun isStalled(nowMs: Long, limitMs: Long): Boolean {
        val started = writeStartedMs
        return started != 0L && nowMs - started > limitMs
    }

    fun sendMetadata(payload: ByteArray) {
        queue.offer(MediaQueue.Item(MediaQueue.Kind.ESSENTIAL, ChunkWriter.CSID_DATA, RtmpMessage.DATA_AMF0, 0, payload))
    }

    /** 인코더의 설정 버퍼 (Annex-B SPS·PPS). 인코더를 새로 만들 때마다 다시 온다. */
    fun onVideoConfig(annexB: ByteArray) {
        takeParameterSets(annexB)
        sequenceHeaderSent = false
    }

    fun onVideoFrame(ptsUs: Long, annexB: ByteArray, keyFrame: Boolean) {
        // 설정 버퍼 없이 키프레임 안에 SPS·PPS를 넣는 인코더도 있다.
        if (keyFrame && (sps == null || pps == null || !sequenceHeaderSent)) takeParameterSets(annexB)
        if (!sequenceHeaderSent) {
            if (!keyFrame) return // 시퀀스 헤더 뒤의 첫 프레임은 키프레임이어야 한다
            val s = sps ?: return
            val p = pps ?: return
            if (baseUs == NONE) baseUs = ptsUs
            val ts = videoTimestamp(ptsUs)
            queue.offer(MediaQueue.Item(MediaQueue.Kind.ESSENTIAL, ChunkWriter.CSID_VIDEO, RtmpMessage.VIDEO, ts, Flv.avcSequenceHeader(s, p)))
            sequenceHeaderSent = true
        }
        val body = Flv.avcFrame(annexB, keyFrame) ?: return
        val kind = if (keyFrame) MediaQueue.Kind.KEY_FRAME else MediaQueue.Kind.FRAME
        queue.offer(MediaQueue.Item(kind, ChunkWriter.CSID_VIDEO, RtmpMessage.VIDEO, videoTimestamp(ptsUs), body))
    }

    fun onAudioConfig(audioSpecificConfig: ByteArray) {
        queue.offer(
            MediaQueue.Item(
                MediaQueue.Kind.ESSENTIAL, ChunkWriter.CSID_AUDIO, RtmpMessage.AUDIO, maxOf(lastAudioMs, 0),
                Flv.aacSequenceHeader(audioSpecificConfig),
            ),
        )
    }

    fun onAudioFrame(ptsUs: Long, data: ByteBuffer) {
        val base = baseUs
        if (base == NONE || ptsUs < base) return
        val ts = maxOf((ptsUs - base) / 1000, lastAudioMs, 0)
        lastAudioMs = ts
        queue.offer(MediaQueue.Item(MediaQueue.Kind.AUDIO, ChunkWriter.CSID_AUDIO, RtmpMessage.AUDIO, ts, Flv.aacFrame(data)))
    }

    private fun videoTimestamp(ptsUs: Long): Long {
        val ts = maxOf((ptsUs - baseUs) / 1000, lastVideoMs)
        lastVideoMs = ts
        return ts
    }

    private fun takeParameterSets(annexB: ByteArray) {
        for (nal in Flv.splitAnnexB(annexB)) {
            when (Flv.nalType(nal)) {
                Flv.NAL_SPS -> if (!nal.contentEquals(sps)) {
                    sps = nal
                    sequenceHeaderSent = false
                }
                Flv.NAL_PPS -> if (!nal.contentEquals(pps)) {
                    pps = nal
                    sequenceHeaderSent = false
                }
            }
        }
    }

    private fun sendLoop() {
        try {
            while (running) {
                val item = queue.take(500) ?: continue
                writeStartedMs = System.currentTimeMillis()
                publisher.send(item.chunkStreamId, item.type, item.timestampMs, item.payload)
                writeStartedMs = 0L
            }
        } catch (e: IOException) {
            writeStartedMs = 0L
            fail(e.message ?: "연결이 끊김")
        }
    }

    private fun fail(reason: String) {
        if (!running || failed) return
        failed = true
        log("stream failed: $reason")
        onFailed(reason)
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
    }
}
