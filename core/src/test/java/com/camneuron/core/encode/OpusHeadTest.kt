package com.camneuron.core.encode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpusHeadTest {
    @Test
    fun find_extractsHeadFromAndroidConfigBuffer() {
        // Android Opus 인코더의 설정 버퍼: "AOPUSHDR" + 길이(8바이트) + OpusHead + "AOPUSDLY" ...
        val head = OpusHead.standard(channels = 1, sampleRate = 48_000)
        val csd = ByteBuffer.allocate(16 + head.size + 16).order(ByteOrder.LITTLE_ENDIAN)
            .put("AOPUSHDR".toByteArray()).putLong(head.size.toLong()).put(head)
            .put("AOPUSDLY".toByteArray()).putLong(8)
        csd.flip()

        assertArrayEquals(head, OpusHead.find(csd))
        assertEquals(0, csd.position()) // 버퍼 위치는 그대로
    }

    @Test
    fun find_returnsNullWithoutHead() {
        assertNull(OpusHead.find(ByteBuffer.wrap(ByteArray(40))))
    }

    @Test
    fun standard_matchesRfc7845() {
        val head = ByteBuffer.wrap(OpusHead.standard(channels = 2, sampleRate = 48_000)).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(19, head.remaining())
        assertArrayEquals("OpusHead".toByteArray(), ByteArray(8).also { head.get(it) })
        assertEquals(1, head.get().toInt()) // version
        assertEquals(2, head.get().toInt()) // channels
        assertEquals(312, head.getShort().toInt()) // pre-skip
        assertEquals(48_000, head.getInt())
        assertEquals(0, head.getShort().toInt()) // gain
        assertEquals(0, head.get().toInt()) // mapping family
    }
}
