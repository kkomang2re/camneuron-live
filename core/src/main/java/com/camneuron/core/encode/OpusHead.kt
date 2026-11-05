package com.camneuron.core.encode

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opus 디코더 설정 머리말 (RFC 7845 5.1). PC의 FFmpeg 디코더에 그대로 넘긴다. */
object OpusHead {
    const val SIZE = 19

    /** libopus가 48kHz에서 앞에 넣는 지연 (6.5ms). */
    private const val DEFAULT_PRE_SKIP = 312
    private val MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)

    /**
     * 인코더가 준 설정 데이터에서 OpusHead를 찾는다. Android는 앞에 "AOPUSHDR"와 길이를 붙이고
     * 뒤에 "AOPUSDLY" 등을 덧붙이므로 머리말 위치를 직접 찾는다. [buffer]의 위치는 바꾸지 않는다.
     */
    fun find(buffer: ByteBuffer): ByteArray? {
        val bytes = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        for (i in 0..bytes.size - SIZE) {
            if (MAGIC.indices.all { bytes[i + it] == MAGIC[it] }) return bytes.copyOfRange(i, i + SIZE)
        }
        return null
    }

    /** 인코더가 설정 데이터를 주지 않을 때 쓰는 표준 값 (채널 1~2개, 매핑 0). */
    fun standard(channels: Int, sampleRate: Int): ByteArray =
        ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC)
            .put(1) // version
            .put(channels.toByte())
            .putShort(DEFAULT_PRE_SKIP.toShort())
            .putInt(sampleRate)
            .putShort(0) // output gain
            .put(0) // channel mapping family
            .array()
}
