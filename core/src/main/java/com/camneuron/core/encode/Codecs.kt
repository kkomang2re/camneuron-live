package com.camneuron.core.encode

/**
 * 코덱 식별값 (네 글자 FourCC). 캠뉴런의 OBS 프로토콜(PROTOCOL.md)이 이 값을 그대로 쓰므로 바꾸면 안 된다.
 */
object Codecs {
    const val H264 = 0x68323634 // 'h264'
    const val H265 = 0x68323635 // 'h265'
    const val OPUS = 0x6F707573 // 'opus'
    const val PCM = 0x70636D20 // 'pcm ': 16비트 little-endian, 채널을 섞어 배치

    /** AAC-LC. 캠뉴런 프로토콜에는 없고 야외 방송(RTMP)만 쓴다. */
    const val AAC = 0x61616320 // 'aac '
}
