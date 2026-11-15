package com.camneuron.live

import kotlin.math.log10

/**
 * 방송 중 마이크 음량 (화면의 음량 막대). 소리 스레드가 20ms마다 [push]하고 화면이 0.1초마다 [take]한다.
 * 그 사이의 가장 큰 값만 남긴다. 마이크를 끄면 인코더가 무음을 넣으므로 막대도 0이 된다.
 */
object AudioMeter {
    /** 이 값(dB)보다 작은 소리는 막대에 안 보인다. */
    private const val FLOOR_DB = -60f

    /** 소리 인코더가 도는 중인지 (마이크 권한이 없거나 마이크가 멈추면 false). */
    @Volatile
    var active = false

    @Volatile
    private var peak = 0f

    fun push(value: Float) {
        if (value > peak) peak = value
    }

    /** 지난번 [take] 뒤의 최댓값 (0~1). */
    fun take(): Float {
        val value = peak
        peak = 0f
        return value
    }

    /** 음량(0~1)을 막대 길이(0~1)로. 귀에 들리는 크기에 맞게 dB로 편다: -60 dB 아래는 0, 0 dB는 1. */
    fun bar(peak: Float): Float {
        if (peak <= 0f) return 0f
        val db = 20f * log10(peak)
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    }
}
