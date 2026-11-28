package com.camneuron.live

import com.camneuron.live.rtmp.RtmpPublisher

/**
 * 야외 방송(모바일 데이터)의 네트워크 대응 값. 방송 세션([LiveSession])과 PC 측정 도구(tools/net-sim)가 함께 쓴다.
 *
 * 값은 PC에서 느려졌다 빨라지는 망을 흉내 내 잰 결과로 정했다 (tools/net-sim, README의 "지연 측정").
 */
object LiveNet {
    /**
     * 보낼 차례를 기다리는 영상·소리의 상한 (초): 원래 비트레이트로 이만큼의 바이트, 그리고 비트레이트를 낮췄어도 이만큼의
     * 시간. 이보다 오래 기다리면 쌓인 것을 모두 버리고 건너뛴다 (MediaQueue). 폰 안에서 방송이 밀릴 수 있는 상한이다.
     * 줄이면 시청자에게 늦게 닿는 양이 줄어드는 대신 망이 잠깐 느려질 때 건너뛰는 일이 늘어난다.
     */
    const val QUEUE_SECONDS = 2

    /**
     * 망이 느릴 때 내려가는 비트레이트 단계 (원래 비트레이트에 대한 비율). 0.0.13까지는 35%(4.5 Mbps면 1.5 Mbps)가
     * 바닥이어서, 업로드가 그보다 느린 곳(사람 많은 곳, 건물 안, 폰이 뜨거워 통신이 느려질 때)에서는 방송이 계속 끊겼고
     * 그때마다 시청자 지연이 늘었다. 화질이 깨져 보여도 끊기지 않는 쪽이 낫다: 해상도는 그대로 두고 비트레이트만 낮춘다.
     */
    private val PERCENT = intArrayOf(100, 70, 50, 35, 22, 14, 9)
    const val MAX_LEVEL = 6

    /** 가장 낮은 영상 비트레이트. 소리(160 kbps)와 합쳐 600 kbps쯤의 업로드면 끊기지 않는다. */
    const val MIN_BITRATE = 400_000

    /** [level]단계의 영상 비트레이트. [MIN_BITRATE]보다 낮추지는 않는다 (원래 그보다 낮으면 그대로). */
    fun limitBitrate(bitrate: Int, level: Int): Int {
        if (level <= 0) return bitrate
        val scaled = (bitrate.toLong() * PERCENT[level.coerceAtMost(MAX_LEVEL)] / 100).toInt()
        return maxOf(scaled, minOf(bitrate, MIN_BITRATE))
    }

    /**
     * 잘 보내지는 상태가 이만큼 이어질 때마다 화질을 한 단계 되돌린다. 올렸다가 넘쳐도 건너뛰기 전에 도로 내려오므로
     * ([UploadGovernor.onDelay]) 짧게 잡는다: 가장 낮은 단계에서 원래 화질까지 24초.
     */
    const val RECOVER_MS = 4_000L

    fun governor() = UploadGovernor(maxLevel = MAX_LEVEL, recoverMs = RECOVER_MS)

    /** 소리가 차지하는 양 (AAC 160 kbps와 RTMP 포장). 영상 비트레이트에 더해 망에 실리는 양을 어림한다. */
    const val AUDIO_BPS = 170_000

    /** [level]단계에서 망에 실리는 양 (영상과 소리, bps). [bitrate]는 원래 영상 비트레이트. */
    fun totalBitrate(bitrate: Int, level: Int): Int = limitBitrate(bitrate, level) + AUDIO_BPS

    /** 대기열이 이보다 덜 밀려 있으면 비어 있는 것으로 본다. */
    const val CALM_MS = 200L

    /**
     * 소켓 송신 버퍼 크기: 지금 보내는 양([totalBps])의 0.25초 분량 ([RtmpPublisher.sendBufferFor]). 소켓 버퍼에 든 것은
     * 대기열이 모르는 밀림이라, 비트레이트를 낮췄으면 함께 줄여야 한다.
     *
     * 늘릴 때는 바로 늘린다. **줄이는 것은 대기열이 비어 있을 때** ([queueDelayMs]가 [CALM_MS] 이하) **나 방금 건너뛰었을
     * 때**([skipped])만 한다. 밀려 있을 때 줄이면 이미 든 것이 새 크기 아래로 빠질 때까지 쓰기가 막혀, 그만큼 대기열이 더
     * 밀리고 건너뛰게 된다. 대기열이 비어 있을 때는 소켓 버퍼도 비어 있어 줄여도 막히는 것이 없다. 건너뛴 직후에는 소켓에
     * 남은 옛것이 먼저 빠져야 하므로 막혀도 된다 (그러지 않으면 아주 느린 망에서는 줄일 틈이 영영 없다).
     */
    fun sendBuffer(currentBytes: Int, totalBps: Int, queueDelayMs: Long, skipped: Boolean): Int {
        val wanted = RtmpPublisher.sendBufferFor(totalBps)
        return if (wanted >= currentBytes || skipped || queueDelayMs <= CALM_MS) wanted else currentBytes
    }
}
