package com.camneuron.core.encode

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AacConfigTest {
    @Test
    fun audioSpecificConfig_lc48kStereo() {
        // 00010 0011 0010 000 = 0x11 0x90 (FFmpeg·OBS가 48kHz 스테레오 AAC-LC에 쓰는 값)
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), AacConfig.audioSpecificConfig(48_000, 2))
    }

    @Test
    fun audioSpecificConfig_lc44kMono() {
        // 00010 0100 0001 000 = 0x12 0x08
        assertArrayEquals(byteArrayOf(0x12, 0x08), AacConfig.audioSpecificConfig(44_100, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun audioSpecificConfig_rejectsUnknownRate() {
        AacConfig.audioSpecificConfig(47_000, 2)
    }
}
