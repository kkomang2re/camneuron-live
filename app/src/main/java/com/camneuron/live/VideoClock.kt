package com.camneuron.live

import kotlin.math.abs

/**
 * 카메라가 프레임에 찍는 시각(µs)을 소리와 같은 시계(MONOTONIC: System.nanoTime)로 옮긴다.
 *
 * 카메라의 시계는 기기마다, 같은 폰에서도 카메라마다 다르다: 부팅 뒤 흐른 시간(BOOTTIME), MONOTONIC, 또는 어디서
 * 시작하는지 알 수 없는 시계. 방송을 시작할 때 한 번만 정해 두면, 전면·후면 카메라의 시계가 다른 폰에서는 카메라를
 * 바꾸는 순간 영상 시각이 크게 뛰거나 멈춘다 (서버가 연결을 끊거나 시청자 플레이어가 멈춘다).
 * 그래서 인코더를 새로 만들 때마다 [reset]하고, 그 인코더의 첫 프레임으로 다시 정한다.
 */
class VideoClock {
    private var offsetUs = 0L
    private var decided = false

    /** 새 인코더(카메라가 바뀌었을 수 있다)를 시작할 때. 다음 프레임으로 시계를 다시 정한다. */
    fun reset() {
        decided = false
    }

    /** [ptsUs]를 MONOTONIC으로. [monotonicNowUs]·[boottimeNowUs]는 이 프레임을 받은 지금의 두 시계 값이다. */
    fun toMonotonic(ptsUs: Long, monotonicNowUs: Long, boottimeNowUs: Long): Long {
        if (!decided) {
            offsetUs = offsetFor(ptsUs, monotonicNowUs, boottimeNowUs)
            decided = true
        }
        return ptsUs + offsetUs
    }

    companion object {
        /** 찍고 나서 인코더에서 나오기까지 이보다 오래 걸리지는 않는다. 더 벌어져 있으면 모르는 시계로 본다. */
        const val MAX_LATENCY_US = 2_000_000L

        /**
         * 프레임 시각에 더할 값. 지금 시각과 더 가까운 쪽 시계로 보고, 둘 다 [MAX_LATENCY_US]보다 멀면 모르는 시계이므로
         * 이 프레임을 지금 시각에 맞춘다 (찍은 뒤 나오기까지 걸린 만큼 영상이 소리보다 늦게 표시되지만, 시각이 뛰지는 않는다).
         */
        fun offsetFor(ptsUs: Long, monotonicNowUs: Long, boottimeNowUs: Long): Long {
            val fromMonotonic = abs(monotonicNowUs - ptsUs)
            val fromBoottime = abs(boottimeNowUs - ptsUs)
            return when {
                minOf(fromMonotonic, fromBoottime) > MAX_LATENCY_US -> monotonicNowUs - ptsUs
                fromMonotonic <= fromBoottime -> 0L
                else -> monotonicNowUs - boottimeNowUs
            }
        }
    }
}
