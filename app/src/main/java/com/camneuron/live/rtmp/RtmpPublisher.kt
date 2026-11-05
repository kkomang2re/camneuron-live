package com.camneuron.live.rtmp

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Random

/** 사용자에게 그대로 보여 줄 수 있는 RTMP 오류. */
class RtmpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * RTMP 서버에 방송을 올리는 최소한의 클라이언트: 핸드셰이크, connect, createStream, publish, 미디어 전송.
 * 순서는 OBS(librtmp)·FFmpeg와 같다. Android에 의존하지 않아 PC 단위 테스트에서 가짜 서버로 시험한다.
 *
 * [connect]는 호출한 스레드를 막고 publish가 받아들여질 때까지 기다린다. 그 뒤 [startReader]로 서버 메시지
 * (받은 양 확인 요청, ping, 방송 중단 알림)를 처리하고, 미디어는 [send]로 한 스레드에서 보낸다.
 */
class RtmpPublisher(
    private val url: RtmpUrl,
    private val streamKey: String,
    private val log: (String) -> Unit = {},
) : Closeable {
    private val socket = Socket()
    private lateinit var writer: ChunkWriter
    private lateinit var reader: ChunkReader
    private var readerThread: Thread? = null

    /** createStream이 준 메시지 스트림 번호. 미디어는 이 번호로 보낸다. */
    var streamId = 0
        private set

    private var windowAckSize = 0L
    private var lastAckBytes = 0L
    private var transaction = 0

    @Volatile
    private var closed = false

    val bytesSent: Long get() = if (::writer.isInitialized) writer.bytesWritten else 0L

    fun connect(timeoutMs: Int = CONNECT_TIMEOUT_MS) {
        try {
            socket.connect(InetSocketAddress(url.host, url.port), timeoutMs)
        } catch (e: IOException) {
            throw RtmpException("서버에 연결하지 못함 (${url.host}:${url.port})", e)
        }
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = BufferedOutputStream(socket.getOutputStream(), OUTPUT_BUFFER)
            handshake(input, output)
            writer = ChunkWriter(output)
            reader = ChunkReader(input)
            writer.setChunkSize(OUT_CHUNK_SIZE)

            val connectTxn = sendCommand(
                "connect",
                mapOf(
                    "app" to url.app,
                    "type" to "nonprivate",
                    "flashVer" to FLASH_VER,
                    "swfUrl" to url.tcUrl,
                    "tcUrl" to url.tcUrl,
                ),
                streamId = 0,
            )
            awaitResult(connectTxn, "connect")
            log("connected to ${url.tcUrl}")

            // OBS와 같은 순서. releaseStream·FCPublish는 응답하지 않는 서버도 있어 기다리지 않는다.
            sendCommand("releaseStream", null, streamKey, streamId = 0)
            sendCommand("FCPublish", null, streamKey, streamId = 0)
            val createTxn = sendCommand("createStream", null, streamId = 0)
            val result = awaitResult(createTxn, "createStream")
            streamId = (result.getOrNull(3) as? Double)?.toInt() ?: throw RtmpException("서버가 스트림 번호를 주지 않음")

            sendCommand("publish", null, streamKey, "live", streamId = streamId)
            awaitPublishStart()
            log("publishing on stream $streamId")
            socket.soTimeout = 0 // 이제부터 서버는 거의 말이 없다. 끊김은 보내기 실패로 안다.
        } catch (e: RtmpException) {
            close()
            throw e
        } catch (e: SocketTimeoutException) {
            close()
            throw RtmpException("서버가 응답하지 않음", e)
        } catch (e: EOFException) {
            close()
            throw RtmpException("서버가 연결을 끊음 · 스트림 키와 서버 주소를 확인하세요", e)
        } catch (e: IOException) {
            close()
            throw RtmpException("연결이 끊김: ${e.message}", e)
        }
    }

    /** 방송 중에 서버가 보내는 메시지를 처리한다. 연결이 끊기거나 서버가 방송을 멈추면 [onClosed]. */
    fun startReader(onClosed: (String) -> Unit) {
        readerThread = Thread({
            val reason = try {
                while (!closed) {
                    val message = reader.readMessage()
                    if (handleControl(message)) continue
                    if (message.type == RtmpMessage.COMMAND_AMF0) {
                        val values = Amf0.decodeAll(message.payload)
                        if (values.firstOrNull() == "onStatus") {
                            val info = values.getOrNull(3) as? Map<*, *>
                            val level = info?.get("level") as? String
                            if (level == "error") throw RtmpException("서버가 방송을 멈춤: ${info["code"]}")
                        }
                    }
                }
                null
            } catch (e: IOException) {
                if (closed) null else e.message ?: "연결이 끊김"
            }
            if (reason != null) onClosed(reason)
        }, "rtmp-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** 미디어나 데이터 메시지 하나를 보낸다. 한 스레드에서만 부른다. 연결이 끊기면 IOException. */
    fun send(chunkStreamId: Int, type: Int, timestampMs: Long, payload: ByteArray) {
        writer.write(chunkStreamId, RtmpMessage(type, streamId, timestampMs, payload))
        writer.flush()
    }

    /** 방송을 끝낸다고 알리고(되도록) 연결을 닫는다. 몇 번 불러도 된다. */
    override fun close() {
        if (closed) return
        closed = true
        if (streamId != 0 && ::writer.isInitialized && !socket.isClosed) {
            // 네트워크가 막혀 송신 스레드가 쓰기에 걸려 있으면 이 인사도 막히므로 따로 보내고 오래 기다리지 않는다.
            val goodbye = Thread({
                runCatching {
                    sendCommand("FCUnpublish", null, streamKey, streamId = 0)
                    sendCommand("deleteStream", null, streamId.toDouble(), streamId = 0)
                }
            }, "rtmp-goodbye").apply {
                isDaemon = true
                start()
            }
            goodbye.join(GOODBYE_TIMEOUT_MS)
        }
        runCatching { socket.close() }
        readerThread?.let { if (it !== Thread.currentThread()) it.join(1000) }
    }

    // ------------------------------------------------------------------------------------------

    private fun handshake(input: DataInputStream, output: BufferedOutputStream) {
        // 단순 핸드셰이크: C0(버전 3) + C1(시각 4, 0 4, 무작위 1528). S1을 그대로 C2로 돌려준다.
        val c1 = ByteArray(HANDSHAKE_SIZE)
        Random().nextBytes(c1)
        for (i in 0 until 8) c1[i] = 0
        output.write(RTMP_VERSION)
        output.write(c1)
        output.flush()

        val s0 = input.readUnsignedByte()
        if (s0 != RTMP_VERSION) throw RtmpException("RTMP 서버가 아님 (버전 $s0)")
        val s1 = ByteArray(HANDSHAKE_SIZE)
        input.readFully(s1)
        output.write(s1)
        output.flush()
        val s2 = ByteArray(HANDSHAKE_SIZE)
        input.readFully(s2)
    }

    /** AMF0 명령을 보내고 그 거래 번호를 돌려준다. publish 등 응답을 기다리지 않는 것은 0을 쓰는 서버도 있으나 번호를 늘려 보낸다. */
    private fun sendCommand(name: String, vararg args: Any?, streamId: Int): Int {
        val txn = ++transaction
        val payload = Amf0.encode(name, txn, *args)
        writer.write(ChunkWriter.CSID_COMMAND, RtmpMessage(RtmpMessage.COMMAND_AMF0, streamId, 0, payload))
        writer.flush()
        return txn
    }

    /** [txn]에 대한 _result를 기다린다. _error면 서버가 준 설명으로 실패한다. */
    private fun awaitResult(txn: Int, what: String): List<Any?> {
        while (true) {
            val message = reader.readMessage()
            if (handleControl(message) || message.type != RtmpMessage.COMMAND_AMF0) continue
            val values = Amf0.decodeAll(message.payload)
            val name = values.firstOrNull() as? String ?: continue
            val id = (values.getOrNull(1) as? Double)?.toInt()
            if (id != txn) continue
            when (name) {
                "_result" -> return values
                "_error" -> {
                    val info = values.getOrNull(3) as? Map<*, *>
                    throw RtmpException("서버가 $what 거부: ${info?.get("description") ?: info?.get("code") ?: "이유 모름"}")
                }
            }
        }
    }

    private fun awaitPublishStart() {
        while (true) {
            val message = reader.readMessage()
            if (handleControl(message) || message.type != RtmpMessage.COMMAND_AMF0) continue
            val values = Amf0.decodeAll(message.payload)
            val name = values.firstOrNull() as? String
            if (name == "_error") throw RtmpException("서버가 방송을 거부함 · 스트림 키를 확인하세요")
            if (name != "onStatus") continue
            val info = values.getOrNull(3) as? Map<*, *> ?: continue
            val code = info["code"] as? String ?: continue
            when {
                code == "NetStream.Publish.Start" -> return
                info["level"] == "error" || code.contains("BadName") || code.contains("Failed") || code.contains("Rejected") ->
                    throw RtmpException("서버가 방송을 거부함 ($code) · 스트림 키를 확인하세요")
            }
        }
    }

    /** 프로토콜 제어 메시지면 처리하고 true. 받은 양 확인(ack)도 여기서 보낸다. */
    private fun handleControl(message: RtmpMessage): Boolean {
        val handled = when (message.type) {
            RtmpMessage.SET_CHUNK_SIZE, RtmpMessage.ABORT, RtmpMessage.ACKNOWLEDGEMENT -> true
            RtmpMessage.WINDOW_ACK_SIZE -> {
                if (message.payload.size >= 4) windowAckSize = ChunkReader.int32(message.payload, 0).toLong() and 0xFFFFFFFFL
                true
            }
            RtmpMessage.SET_PEER_BANDWIDTH -> {
                if (message.payload.size >= 4) {
                    val size = ChunkReader.int32(message.payload, 0).toLong() and 0xFFFFFFFFL
                    writer.write(
                        ChunkWriter.CSID_PROTOCOL,
                        RtmpMessage(RtmpMessage.WINDOW_ACK_SIZE, 0, 0, RtmpMessage.int32(size)),
                    )
                    writer.flush()
                }
                true
            }
            RtmpMessage.USER_CONTROL -> {
                val payload = message.payload
                if (payload.size >= 6 && ((payload[0].toInt() shl 8) or (payload[1].toInt() and 0xFF)) == RtmpMessage.UC_PING_REQUEST) {
                    val pong = byteArrayOf(0, RtmpMessage.UC_PING_RESPONSE.toByte()) + payload.copyOfRange(2, 6)
                    writer.write(ChunkWriter.CSID_PROTOCOL, RtmpMessage(RtmpMessage.USER_CONTROL, 0, 0, pong))
                    writer.flush()
                }
                true
            }
            else -> false
        }
        val read = reader.bytesRead
        if (windowAckSize > 0 && read - lastAckBytes >= windowAckSize) {
            lastAckBytes = read
            writer.write(
                ChunkWriter.CSID_PROTOCOL,
                RtmpMessage(RtmpMessage.ACKNOWLEDGEMENT, 0, 0, RtmpMessage.int32(read and 0xFFFFFFFFL)),
            )
            writer.flush()
        }
        return handled
    }

    companion object {
        private const val RTMP_VERSION = 3
        private const val HANDSHAKE_SIZE = 1536
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val OUT_CHUNK_SIZE = 4096
        private const val OUTPUT_BUFFER = 64 * 1024
        private const val FLASH_VER = "FMLE/3.0 (compatible; FMSc/1.0)"
        private const val GOODBYE_TIMEOUT_MS = 1000L
    }
}
