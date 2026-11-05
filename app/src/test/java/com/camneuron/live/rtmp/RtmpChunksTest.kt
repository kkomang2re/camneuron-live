package com.camneuron.live.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RtmpChunksTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun writer_firstChunkHasFullHeader() {
        val out = ByteArrayOutputStream()
        ChunkWriter(out).write(3, RtmpMessage(RtmpMessage.COMMAND_AMF0, 1, 0x010203, bytes(0xAA, 0xBB)))
        assertArrayEquals(
            bytes(
                0x03, // fmt 0, csid 3
                0x01, 0x02, 0x03, // 시각
                0x00, 0x00, 0x02, // 길이
                0x14, // 형식 20
                0x01, 0x00, 0x00, 0x00, // 스트림 1 (little-endian)
                0xAA, 0xBB,
            ),
            out.toByteArray(),
        )
    }

    @Test
    fun writer_splitsAtChunkSizeWithType3Headers() {
        val out = ByteArrayOutputStream()
        val payload = ByteArray(300) { it.toByte() }
        ChunkWriter(out).write(6, RtmpMessage(RtmpMessage.VIDEO, 1, 40, payload))
        val data = out.toByteArray()
        // 12바이트 헤더 + 128 + (1 + 128) + (1 + 44)
        assertEquals(12 + 128 + 1 + 128 + 1 + 44, data.size)
        assertEquals(0xC6, data[12 + 128].toInt() and 0xFF) // fmt 3, csid 6
        assertEquals(0xC6, data[12 + 128 + 1 + 128].toInt() and 0xFF)
        assertEquals(payload[128], data[12 + 128 + 1])
    }

    @Test
    fun writer_extendedTimestampRepeatsInContinuationChunks() {
        val out = ByteArrayOutputStream()
        val ts = 0x01234567L
        ChunkWriter(out).write(4, RtmpMessage(RtmpMessage.AUDIO, 1, ts, ByteArray(200)))
        val data = out.toByteArray()
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF), data.copyOfRange(1, 4))
        assertArrayEquals(bytes(0x01, 0x23, 0x45, 0x67), data.copyOfRange(12, 16))
        val next = 16 + 128
        assertEquals(0xC4, data[next].toInt() and 0xFF)
        assertArrayEquals(bytes(0x01, 0x23, 0x45, 0x67), data.copyOfRange(next + 1, next + 5))
        assertEquals(next + 5 + 72, data.size)
    }

    @Test
    fun writer_setChunkSizeAppliesToLaterMessages() {
        val out = ByteArrayOutputStream()
        val writer = ChunkWriter(out)
        writer.setChunkSize(4096)
        assertArrayEquals(
            bytes(0x02, 0, 0, 0, 0, 0, 4, 0x01, 0, 0, 0, 0, 0x00, 0x00, 0x10, 0x00),
            out.toByteArray(),
        )
        out.reset()
        writer.write(6, RtmpMessage(RtmpMessage.VIDEO, 1, 0, ByteArray(1000)))
        assertEquals(12 + 1000, out.size())
    }

    @Test
    fun reader_readsWhatWriterWrote() {
        val out = ByteArrayOutputStream()
        val writer = ChunkWriter(out)
        val big = ByteArray(5000) { (it * 7).toByte() }
        writer.write(6, RtmpMessage(RtmpMessage.VIDEO, 1, 33, big))
        writer.write(4, RtmpMessage(RtmpMessage.AUDIO, 1, 0x1000000, bytes(1, 2, 3)))
        val reader = ChunkReader(ByteArrayInputStream(out.toByteArray()))
        val video = reader.readMessage()
        assertEquals(RtmpMessage.VIDEO, video.type)
        assertEquals(33L, video.timestamp)
        assertArrayEquals(big, video.payload)
        val audio = reader.readMessage()
        assertEquals(0x1000000L, audio.timestamp)
        assertArrayEquals(bytes(1, 2, 3), audio.payload)
        assertEquals(out.size().toLong(), reader.bytesRead)
    }

    @Test
    fun reader_handlesCompressedHeadersAndServerChunkSize() {
        val data = bytes(
            // 서버가 청크 크기를 4로 바꾼다
            0x02, 0, 0, 0, 0, 0, 4, 0x01, 0, 0, 0, 0, 0, 0, 0, 4,
            // fmt 0: csid 3, ts 10, 길이 6, 형식 20, 스트림 0 → 청크 4바이트 + fmt 3 이어서 2바이트
            0x03, 0, 0, 10, 0, 0, 6, 0x14, 0, 0, 0, 0, 1, 2, 3, 4,
            0xC3, 5, 6,
            // fmt 2: 시각 차이 5, 같은 길이·형식
            0x83, 0, 0, 5, 7, 8, 9, 10,
            0xC3, 11, 12,
            // fmt 3으로 새 메시지: 이전 차이(5)를 다시 더한다
            0xC3, 13, 14, 15, 16,
            0xC3, 17, 18,
            // fmt 1: 차이 1, 길이 1, 형식 8
            0x43, 0, 0, 1, 0, 0, 1, 0x08, 99,
        )
        val reader = ChunkReader(ByteArrayInputStream(data))
        val control = reader.readMessage()
        assertEquals(RtmpMessage.SET_CHUNK_SIZE, control.type)
        assertEquals(4, reader.chunkSize)

        val first = reader.readMessage()
        assertEquals(10L, first.timestamp)
        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6), first.payload)
        val second = reader.readMessage()
        assertEquals(15L, second.timestamp)
        assertArrayEquals(bytes(7, 8, 9, 10, 11, 12), second.payload)
        val third = reader.readMessage()
        assertEquals(20L, third.timestamp)
        assertArrayEquals(bytes(13, 14, 15, 16, 17, 18), third.payload)
        val fourth = reader.readMessage()
        assertEquals(21L, fourth.timestamp)
        assertEquals(RtmpMessage.AUDIO, fourth.type)
        assertArrayEquals(bytes(99), fourth.payload)
    }

    @Test
    fun reader_twoByteChunkStreamId() {
        val out = ByteArrayOutputStream()
        ChunkWriter(out).write(100, RtmpMessage(RtmpMessage.COMMAND_AMF0, 0, 0, bytes(1)))
        val data = out.toByteArray()
        assertEquals(0x00, data[0].toInt())
        assertEquals(36, data[1].toInt())
        assertArrayEquals(bytes(1), ChunkReader(ByteArrayInputStream(data)).readMessage().payload)
    }
}
