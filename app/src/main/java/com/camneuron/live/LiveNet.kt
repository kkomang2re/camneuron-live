package com.camneuron.live

import com.camneuron.core.net.NetGovernor

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

    /** 해상도는 바꾸지 않으므로 3단계(35%)까지만 낮춘다. 4단계는 비트레이트가 같고 되돌아오는 데만 30초 더 걸린다. */
    const val MAX_LEVEL = 3

    /**
     * 잘 보내지는 상태가 이만큼 이어질 때마다 화질을 한 단계 되돌린다. 15초(집 안 Wi-Fi용 값)로는 망이 잠깐씩 느려지는
     * 야외에서 낮춘 화질에 너무 오래 머물렀다 (45초 좋고 6초 느린 망에서 평균 3.4 Mbps, 8초로는 3.9 Mbps. 지연과 버린
     * 프레임은 같았다). 5초는 망이 자주 나빠질 때 버리는 프레임이 늘었다.
     */
    const val RECOVER_MS = 8_000L

    fun governor() = NetGovernor(maxLevel = MAX_LEVEL, baseRecoverMs = RECOVER_MS)
}
