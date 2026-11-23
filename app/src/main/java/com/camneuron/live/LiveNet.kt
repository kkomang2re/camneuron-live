package com.camneuron.live

import com.camneuron.core.net.NetGovernor
import com.camneuron.live.rtmp.RtmpPublisher

/**
 * 야외 방송(모바일 데이터)의 네트워크 대응 값. 방송 세션([LiveSession])과 PC 측정 도구(tools/net-sim)가 함께 쓴다.
 *
 * 값은 PC에서 느려졌다 빨라지는 망을 흉내 내 잰 결과로 정했다 (tools/net-sim, README의 "지연 측정").
 */
object LiveNet {
    /**
     * 보낼 차례를 기다리는 영상의 상한 (초): 원래 비트레이트로 이만큼의 바이트, 그리고 비트레이트를 낮췄어도 이만큼의 시간.
     * 이보다 오래 기다린 영상은 버린다. 줄이면 방송 지연의 상한이 낮아지는 대신 망이 잠깐 느려질 때 버리는 영상이 늘어난다.
     */
    const val QUEUE_SECONDS = 2

    /**
     * 보낼 차례를 기다리는 소리의 상한 (초). 영상을 다 버리고도 소리가 이보다 오래 기다리면 소리도 버리고 건너뛴다
     * (MediaQueue). 이 값이 폰 안에서 방송이 밀릴 수 있는 상한이다: 전에는 소리에 시간 제한이 없어, 소리조차 다 못 보내는
     * 망(폰이 뜨거워 통신이 느려질 때 등)이 몇 분 이어지면 수십 초씩 밀렸고 망이 좋아져도 시청자 화면은 늦은 채로 남았다.
     * 영상보다 길게 둔 것은 망이 잠깐 느려질 때 소리까지 끊지는 않으려는 것이다.
     */
    const val AUDIO_QUEUE_SECONDS = 3

    /** 해상도는 바꾸지 않으므로 3단계(35%)까지만 낮춘다. 4단계는 비트레이트가 같고 되돌아오는 데만 30초 더 걸린다. */
    const val MAX_LEVEL = 3

    /**
     * 잘 보내지는 상태가 이만큼 이어질 때마다 화질을 한 단계 되돌린다. 15초(집 안 Wi-Fi용 값)로는 망이 잠깐씩 느려지는
     * 야외에서 낮춘 화질에 너무 오래 머물렀다 (45초 좋고 6초 느린 망에서 평균 3.4 Mbps, 8초로는 3.9 Mbps. 지연과 버린
     * 프레임은 같았다). 5초는 망이 자주 나빠질 때 버리는 프레임이 늘었다.
     */
    const val RECOVER_MS = 8_000L

    fun governor() = NetGovernor(maxLevel = MAX_LEVEL, baseRecoverMs = RECOVER_MS)

    /**
     * 소켓 송신 버퍼 크기를 1초마다 정한다. 평소에는 지금 비트레이트의 0.25초 분량([RtmpPublisher.sendBufferFor])이다.
     *
     * 대기열이 밀려 있으면 (보내는 쪽이 망 속도만큼만 보내고 있다) 그때 실제로 나간 속도를 망 속도로 보고, 그 0.75초
     * 분량까지 줄인다. 소켓 버퍼에 든 것은 대기열이 모르는 밀림이라, 아주 느린 망에서는 최소 크기(48KB)만으로도 몇 초가
     * 된다 (200 kbps에서 2~4초): 영상을 다시 시작할 때마다 그만큼이 소리 앞에 끼어든다.
     * 더 많이 나가기 시작하면 (망이 빨라졌다) 바로 원래 크기로 돌아온다. 소리만 보내는 동안에는 망이 빨라졌는지 알 수
     * 없으므로 줄인 채로 두되, [FORGET_SECONDS]가 지나면 원래 크기로 돌린다.
     */
    class SendBuffer {
        private var linkBytesPerS = UNKNOWN
        private var clearSeconds = 0

        /**
         * [sentBytesPerS]는 지난 1초 동안 보낸 양 (아직 재지 못했으면 음수), [queueDelayMs]는 그동안 대기열 맨 앞이 가장
         * 오래 기다린 시간. 요청할 버퍼 크기(바이트)를 돌려준다.
         */
        fun update(targetBitrate: Int, sentBytesPerS: Int, queueDelayMs: Long): Int {
            if (sentBytesPerS < 0) {
                // 잰 것이 없다: 그대로 둔다.
            } else if (queueDelayMs >= BACKLOG_MS) {
                linkBytesPerS = sentBytesPerS
                clearSeconds = 0
            } else if (linkBytesPerS != UNKNOWN) {
                linkBytesPerS = maxOf(linkBytesPerS, sentBytesPerS)
                if (++clearSeconds >= FORGET_SECONDS) linkBytesPerS = UNKNOWN
            }
            val normal = RtmpPublisher.sendBufferFor(targetBitrate)
            return if (linkBytesPerS == UNKNOWN) normal else minOf(normal, maxOf(MIN_BYTES, linkBytesPerS / 4 * 3))
        }

        companion object {
            /** 대기열이 이만큼 밀려 있으면 보내는 쪽이 망 속도에 묶여 있는 것으로 본다. */
            const val BACKLOG_MS = 1_000L

            /** 밀림이 없어지고 이만큼 지나면 재 둔 망 속도를 잊는다. */
            const val FORGET_SECONDS = 30

            /** 이보다 작게 하면 왕복 지연이 큰 망에서 버퍼 때문에 속도가 안 나온다. */
            const val MIN_BYTES = 16 * 1024
            private const val UNKNOWN = -1
        }
    }
}
