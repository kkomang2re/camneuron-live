package com.camneuron.live.rtmp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** RTMP 메시지 하나 (청크로 나누기 전). [timestamp]는 ms. */
class RtmpMessage(
    val type: Int,
    val streamId: Int,
    val timestamp: Long,
    val payload: ByteArray,
) {
    companion object {
        const val SET_CHUNK_SIZE = 1
        const val ABORT = 2
        const val ACKNOWLEDGEMENT = 3
        const val USER_CONTROL = 4
        const val WINDOW_ACK_SIZE = 5
        const val SET_PEER_BANDWIDTH = 6
        const val AUDIO = 8
        const val VIDEO = 9
        const val DATA_AMF0 = 18
        const val COMMAND_AMF0 = 20

        /** 사용자 제어 메시지 종류 */
        const val UC_PING_REQUEST = 6
        const val UC_PING_RESPONSE = 7

        fun int32(value: Long): ByteArray = byteArrayOf(
            (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
        )
    }
}

/**
 * 메시지를 청크로 나눠 쓴다. 첫 청크는 항상 형식 0(전체 헤더), 이어지는 청크는 형식 3이다.
 * 매번 형식 0을 써도 규격에 맞고, 헤더가 몇 바이트 늘 뿐 상태를 맞출 일이 없어 단순하다.
 * 여러 스레드가 쓰므로(미디어 송신, 받은 양 알림) 메시지 단위로 잠근다.
 */
class ChunkWriter(private val out: OutputStream) {
    var chunkSize = DEFAULT_CHUNK_SIZE
        private set

    @Volatile
    var bytesWritten = 0L
        private set

    @Synchronized
    fun write(chunkStreamId: Int, message: RtmpMessage) {
        val header = ByteArrayOutputStream(18)
        val ts = message.timestamp and 0xFFFFFFFFL
        val extended = ts >= EXTENDED_TIMESTAMP
        writeBasicHeader(header, 0, chunkStreamId)
        write24(header, if (extended) EXTENDED_TIMESTAMP.toInt() else ts.toInt())
        write24(header, message.payload.size)
        header.write(message.type)
        // 메시지 스트림 번호만 little-endian이다.
        header.write(message.streamId)
        header.write(message.streamId shr 8)
        header.write(message.streamId shr 16)
        header.write(message.streamId shr 24)
        if (extended) header.write(RtmpMessage.int32(ts))
        out.write(header.toByteArray())
        bytesWritten += header.size()

        var offset = 0
        val payload = message.payload
        while (true) {
            val length = minOf(chunkSize, payload.size - offset)
            out.write(payload, offset, length)
            bytesWritten += length
            offset += length
            if (offset >= payload.size) break
            val next = ByteArrayOutputStream(9)
            writeBasicHeader(next, 3, chunkStreamId)
            // FFmpeg·librtmp와 같이 이어지는 청크에도 확장 시각을 다시 넣는다.
            if (extended) next.write(RtmpMessage.int32(ts))
            out.write(next.toByteArray())
            bytesWritten += next.size()
        }
    }

    /** 청크 크기를 바꾸는 제어 메시지를 보내고 이후 메시지에 적용한다. */
    @Synchronized
    fun setChunkSize(size: Int) {
        require(size in 1..0x7FFFFFFF)
        write(CSID_PROTOCOL, RtmpMessage(RtmpMessage.SET_CHUNK_SIZE, 0, 0, RtmpMessage.int32(size.toLong())))
        chunkSize = size
    }

    @Synchronized
    fun flush() = out.flush()

    companion object {
        const val DEFAULT_CHUNK_SIZE = 128
        const val EXTENDED_TIMESTAMP = 0xFFFFFFL

        const val CSID_PROTOCOL = 2
        const val CSID_COMMAND = 3
        const val CSID_AUDIO = 4
        const val CSID_DATA = 5
        const val CSID_VIDEO = 6

        private fun writeBasicHeader(out: ByteArrayOutputStream, fmt: Int, csid: Int) {
            when (csid) {
                in 2..63 -> out.write((fmt shl 6) or csid)
                in 64..319 -> {
                    out.write(fmt shl 6)
                    out.write(csid - 64)
                }
                in 320..65599 -> {
                    out.write((fmt shl 6) or 1)
                    out.write((csid - 64) and 0xFF)
                    out.write((csid - 64) shr 8)
                }
                else -> throw IllegalArgumentException("bad chunk stream id $csid")
            }
        }

        private fun write24(out: ByteArrayOutputStream, v: Int) {
            out.write(v shr 16 and 0xFF)
            out.write(v shr 8 and 0xFF)
            out.write(v and 0xFF)
        }
    }
}

/** 서버가 보낸 청크를 메시지로 모은다. 청크 크기 변경은 스스로 반영한다. 한 스레드에서만 부른다. */
class ChunkReader(input: InputStream) {
    private val input = CountingInput(input)

    private class StreamState {
        var timestamp = 0L
        var delta = 0L
        var length = 0
        var type = 0
        var streamId = 0
        var extended = false
        var buffer: ByteArray? = null
        var received = 0
    }

    private val streams = HashMap<Int, StreamState>()

    var chunkSize = ChunkWriter.DEFAULT_CHUNK_SIZE
        private set

    val bytesRead: Long get() = input.count

    /** 메시지 하나가 다 모일 때까지 읽는다. 연결이 끊기면 EOFException. */
    fun readMessage(): RtmpMessage {
        while (true) {
            val first = input.u8()
            val fmt = first shr 6
            val csid = when (val low = first and 0x3F) {
                0 -> 64 + input.u8()
                1 -> 64 + input.u8() + (input.u8() shl 8)
                else -> low
            }
            val state = streams.getOrPut(csid) { StreamState() }
            val startingMessage = state.buffer == null
            when (fmt) {
                0 -> {
                    val ts = input.u24().toLong()
                    state.length = input.u24()
                    state.type = input.u8()
                    state.streamId = input.u8() or (input.u8() shl 8) or (input.u8() shl 16) or (input.u8() shl 24)
                    state.extended = ts == ChunkWriter.EXTENDED_TIMESTAMP
                    state.timestamp = if (state.extended) input.u32() else ts
                    state.delta = 0
                }
                1, 2 -> {
                    val delta = input.u24().toLong()
                    if (fmt == 1) {
                        state.length = input.u24()
                        state.type = input.u8()
                    }
                    state.extended = delta == ChunkWriter.EXTENDED_TIMESTAMP
                    state.delta = if (state.extended) input.u32() else delta
                    state.timestamp += state.delta
                }
                else -> {
                    if (state.extended) input.u32() // 반복된 확장 시각
                    // 형식 3으로 새 메시지가 시작되면 이전 차이를 다시 더한다.
                    if (startingMessage) state.timestamp += state.delta
                }
            }
            if (startingMessage) {
                if (state.length > MAX_MESSAGE) throw IOException("RTMP message too large: ${state.length}")
                state.buffer = ByteArray(state.length)
                state.received = 0
            }
            val buffer = state.buffer!!
            val length = minOf(chunkSize, buffer.size - state.received)
            input.readFully(buffer, state.received, length)
            state.received += length
            if (state.received < buffer.size) continue

            state.buffer = null
            val message = RtmpMessage(state.type, state.streamId, state.timestamp, buffer)
            if (message.type == RtmpMessage.SET_CHUNK_SIZE && buffer.size >= 4) {
                chunkSize = (int32(buffer, 0) and 0x7FFFFFFF).coerceAtLeast(1)
            }
            return message
        }
    }

    private class CountingInput(private val input: InputStream) {
        @Volatile
        var count = 0L

        fun u8(): Int {
            val b = input.read()
            if (b < 0) throw EOFException("connection closed")
            count++
            return b
        }

        fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()

        fun u32(): Long = (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()

        fun readFully(buffer: ByteArray, offset: Int, length: Int) {
            var done = 0
            while (done < length) {
                val n = input.read(buffer, offset + done, length - done)
                if (n < 0) throw EOFException("connection closed")
                done += n
            }
            count += length
        }
    }

    companion object {
        private const val MAX_MESSAGE = 16 * 1024 * 1024

        fun int32(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
    }
}
