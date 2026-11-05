package com.camneuron.live.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class FlvTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // High 프로필 4.0 SPS (실제 인코더 출력 모양), 짧은 PPS
    private val sps = bytes(0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9, 0x40, 0x78)
    private val pps = bytes(0x68, 0xEB, 0xE3, 0xCB)

    @Test
    fun splitAnnexB_mixedStartCodes() {
        val data = bytes(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5)
        val nals = Flv.splitAnnexB(data)
        assertEquals(3, nals.size)
        assertArrayEquals(bytes(0x67, 1, 2), nals[0])
        assertArrayEquals(bytes(0x68, 3), nals[1])
        assertArrayEquals(bytes(0x65, 4, 5), nals[2])
    }

    @Test
    fun splitAnnexB_withoutStartCodeIsOneNal() {
        assertEquals(1, Flv.splitAnnexB(bytes(0x41, 1, 2)).size)
        assertEquals(0, Flv.splitAnnexB(ByteArray(0)).size)
    }

    @Test
    fun avcSequenceHeader_layout() {
        val body = Flv.avcSequenceHeader(sps, pps)
        val expected = bytes(0x17, 0x00, 0, 0, 0, 0x01, 0x64, 0x00, 0x28, 0xFF, 0xE1, 0x00, sps.size) +
            sps + bytes(0x01, 0x00, pps.size) + pps
        assertArrayEquals(expected, body)
    }

    @Test
    fun avcFrame_keyFrameDropsParameterSetsAndAud() {
        val idr = bytes(0x65, 0x88, 0x84)
        val annexB = bytes(0, 0, 0, 1, 0x09, 0xF0) + bytes(0, 0, 0, 1) + sps + bytes(0, 0, 0, 1) + pps +
            bytes(0, 0, 0, 1) + idr
        val body = Flv.avcFrame(annexB, keyFrame = true)!!
        assertArrayEquals(bytes(0x17, 0x01, 0, 0, 0, 0, 0, 0, 3) + idr, body)
    }

    @Test
    fun avcFrame_interFrameWithSeiKeepsBothNals() {
        val sei = bytes(0x06, 0x05, 0x01, 0x80)
        val slice = bytes(0x41, 0x9A, 0x00, 0x11)
        val body = Flv.avcFrame(bytes(0, 0, 1) + sei + bytes(0, 0, 1) + slice, keyFrame = false)!!
        assertArrayEquals(bytes(0x27, 0x01, 0, 0, 0, 0, 0, 0, 4) + sei + bytes(0, 0, 0, 4) + slice, body)
    }

    @Test
    fun avcFrame_onlyParameterSetsGivesNull() {
        assertNull(Flv.avcFrame(bytes(0, 0, 0, 1) + sps + bytes(0, 0, 0, 1) + pps, keyFrame = true))
    }

    @Test
    fun aac_sequenceHeaderAndRaw() {
        assertArrayEquals(bytes(0xAF, 0x00, 0x11, 0x90), Flv.aacSequenceHeader(bytes(0x11, 0x90)))
        val buffer = ByteBuffer.wrap(bytes(9, 9, 1, 2, 3)).apply { position(2) }
        assertArrayEquals(bytes(0xAF, 0x01, 1, 2, 3), Flv.aacFrame(buffer))
        assertEquals(2, buffer.position()) // 버퍼 위치는 그대로
    }

    @Test
    fun metadata_isSetDataFrameWithEcmaArray() {
        val values = Amf0.decodeAll(Flv.metadata(1920, 1080, 30, 6000, true, 48000, 2, 160, "test"))
        assertEquals("@setDataFrame", values[0])
        assertEquals("onMetaData", values[1])
        val meta = values[2] as Map<*, *>
        assertEquals(1920.0, meta["width"])
        assertEquals(1080.0, meta["height"])
        assertEquals(7.0, meta["videocodecid"])
        assertEquals(10.0, meta["audiocodecid"])
        assertEquals(true, meta["stereo"])
    }

    @Test
    fun metadata_withoutAudioLeavesAudioFieldsOut() {
        val meta = Amf0.decodeAll(Flv.metadata(1280, 720, 30, 3000, false, 0, 0, 0, "test"))[2] as Map<*, *>
        assertNull(meta["audiocodecid"])
    }
}
