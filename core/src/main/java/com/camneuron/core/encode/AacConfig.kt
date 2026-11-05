package com.camneuron.core.encode

import java.nio.ByteBuffer

/** AAC 디코더 설정(AudioSpecificConfig, ISO 14496-3 1.6.2.1). RTMP는 이 값을 AAC 시퀀스 헤더로 먼저 보낸다. */
object AacConfig {
    /** AAC-LC의 audioObjectType. */
    const val OBJECT_TYPE_LC = 2

    private val SAMPLE_RATES = intArrayOf(
        96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
    )

    /** 인코더가 설정 데이터를 주지 않을 때 쓰는 2바이트 값. 표에 없는 샘플레이트는 받지 않는다. */
    fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray {
        val index = SAMPLE_RATES.indexOf(sampleRate)
        require(index >= 0) { "unsupported AAC sample rate $sampleRate" }
        require(channels in 1..7) { "unsupported AAC channel count $channels" }
        // 5비트 objectType, 4비트 샘플레이트 번호, 4비트 채널 구성, 3비트 0 (frameLength 1024, 확장 없음)
        val bits = (OBJECT_TYPE_LC shl 11) or (index shl 7) or (channels shl 3)
        return byteArrayOf((bits shr 8).toByte(), bits.toByte())
    }

    /** [buffer]의 남은 바이트를 복사한다. 위치는 바꾸지 않는다. */
    fun copy(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
}
