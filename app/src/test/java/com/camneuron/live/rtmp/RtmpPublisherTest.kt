package com.camneuron.live.rtmp

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 같은 PC 안의 가짜 RTMP 서버로 핸드셰이크부터 방송 메시지까지 확인한다. */
class RtmpPublisherTest {
    private val server = ServerSocket(0)
    private val received = LinkedBlockingQueue<RtmpMessage>()
    private val commands = LinkedBlockingQueue<List<Any?>>()
    private var serverThread: Thread? = null

    @After
    fun tearDown() {
        server.close()
        serverThread?.join(2000)
    }

    private fun url() = RtmpUrl("127.0.0.1", server.localPort, "relay")

    /** [rejectPublish]면 publish에 오류 onStatus로 답한다 (스트림 키가 틀린 경우). */
    private fun startServer(rejectPublish: Boolean = false) {
        serverThread = Thread {
            runCatching {
                server.accept().use { socket -> serve(socket, rejectPublish) }
            }
        }.apply { start() }
    }

    private fun serve(socket: Socket, rejectPublish: Boolean) {
        val input = DataInputStream(socket.getInputStream())
        val out = BufferedOutputStream(socket.getOutputStream())
        // 핸드셰이크: C0+C1을 받고 S0+S1+S2, 그다음 C2
        assertEquals(3, input.readUnsignedByte())
        val c1 = ByteArray(1536).also { input.readFully(it) }
        val s1 = ByteArray(1536) { (it * 31).toByte() }
        out.write(3); out.write(s1); out.write(c1); out.flush()
        val c2 = ByteArray(1536).also { input.readFully(it) }
        assertArrayEquals(s1, c2)

        val reader = ChunkReader(input)
        val writer = ChunkWriter(out)
        fun reply(streamId: Int, vararg values: Any?) {
            writer.write(3, RtmpMessage(RtmpMessage.COMMAND_AMF0, streamId, 0, Amf0.encode(*values)))
            writer.flush()
        }
        while (true) {
            val message = reader.readMessage()
            received.put(message)
            if (message.type != RtmpMessage.COMMAND_AMF0) continue
            val values = Amf0.decodeAll(message.payload)
            commands.put(values)
            val txn = values[1]
            when (values[0]) {
                "connect" -> {
                    writer.write(2, RtmpMessage(RtmpMessage.WINDOW_ACK_SIZE, 0, 0, RtmpMessage.int32(2_500_000)))
                    writer.write(2, RtmpMessage(RtmpMessage.SET_PEER_BANDWIDTH, 0, 0, RtmpMessage.int32(2_500_000) + byteArrayOf(2)))
                    writer.setChunkSize(4096)
                    reply(0, "_result", txn, mapOf("fmsVer" to "FMS/3,0,1,123"), mapOf("code" to "NetConnection.Connect.Success"))
                    // 서버가 먼저 보내는 ping
                    writer.write(2, RtmpMessage(RtmpMessage.USER_CONTROL, 0, 0, byteArrayOf(0, 6, 0, 0, 0, 42)))
                    writer.flush()
                }
                "createStream" -> reply(0, "_result", txn, null, 1.0)
                "publish" -> if (rejectPublish) {
                    reply(1, "onStatus", 0, null, mapOf("level" to "error", "code" to "NetStream.Publish.BadName"))
                } else {
                    reply(1, "onStatus", 0, null, mapOf("level" to "status", "code" to "NetStream.Publish.Start"))
                }
            }
        }
    }

    private fun nextMedia(): RtmpMessage {
        while (true) {
            val m = received.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no message")
            if (m.type == RtmpMessage.VIDEO || m.type == RtmpMessage.AUDIO || m.type == RtmpMessage.DATA_AMF0) return m
        }
    }

    @Test
    fun publish_sendsCommandsInOrderAndAnswersPing() {
        startServer()
        val publisher = RtmpPublisher(url(), "secret-key")
        publisher.connect(5000)
        assertEquals(1, publisher.streamId)

        val connect = commands.take()
        assertEquals("connect", connect[0])
        val info = connect[2] as Map<*, *>
        assertEquals("relay", info["app"])
        assertEquals("rtmp://127.0.0.1:${server.localPort}/relay", info["tcUrl"])
        assertEquals(listOf("releaseStream", "FCPublish", "createStream", "publish"), List(4) { commands.take()[0] })

        publisher.startReader { }
        // ping 응답과 SetPeerBandwidth에 대한 Window Ack Size가 온다
        val controls = mutableListOf<RtmpMessage>()
        while (controls.none { it.type == RtmpMessage.USER_CONTROL }) {
            controls += received.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no ping response")
        }
        val pong = controls.first { it.type == RtmpMessage.USER_CONTROL }
        assertArrayEquals(byteArrayOf(0, 7, 0, 0, 0, 42), pong.payload)
        assertTrue(controls.any { it.type == RtmpMessage.WINDOW_ACK_SIZE })
        publisher.close()
    }

    @Test
    fun publish_rejectedKeyThrowsReadableError() {
        startServer(rejectPublish = true)
        try {
            RtmpPublisher(url(), "wrong").connect(5000)
            fail("should be rejected")
        } catch (e: RtmpException) {
            assertTrue(e.message!!, e.message!!.contains("BadName"))
        }
    }

    @Test
    fun connect_refusedPortThrowsReadableError() {
        val port = ServerSocket(0).use { it.localPort }
        try {
            RtmpPublisher(RtmpUrl("127.0.0.1", port, "relay"), "k").connect(2000)
            fail("should fail")
        } catch (e: RtmpException) {
            assertTrue(e.message!!.contains("연결하지 못함"))
        }
    }

    @Test
    fun streamer_sendsHeadersThenMediaWithRelativeTimestamps() {
        startServer()
        val publisher = RtmpPublisher(url(), "k")
        publisher.connect(5000)
        val failures = mutableListOf<String>()
        val streamer = RtmpStreamer(publisher, 1_000_000, onNeedKeyFrame = {}, onFailed = { failures += it })
        streamer.start()

        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F, 0x11)
        val pps = byteArrayOf(0x68, 0x22)
        val start = byteArrayOf(0, 0, 0, 1)
        streamer.sendMetadata(Flv.metadata(1920, 1080, 30, 6000, true, 48000, 2, 160, "test"))
        streamer.onAudioConfig(byteArrayOf(0x11, 0x90.toByte()))
        streamer.onAudioFrame(5_000_000, ByteBuffer.wrap(byteArrayOf(1))) // 첫 영상 전의 소리는 버린다
        streamer.onVideoFrame(4_000_000, start + byteArrayOf(0x41, 1), keyFrame = false) // 헤더 전 중간 프레임은 버린다
        streamer.onVideoConfig(start + sps + start + pps)
        streamer.onVideoFrame(10_000_000, start + byteArrayOf(0x65, 1, 2), keyFrame = true)
        streamer.onVideoFrame(10_033_000, start + byteArrayOf(0x41, 3), keyFrame = false)
        streamer.onAudioFrame(10_021_000, ByteBuffer.wrap(byteArrayOf(7, 8)))

        val meta = nextMedia()
        assertEquals(RtmpMessage.DATA_AMF0, meta.type)
        assertEquals("@setDataFrame", Amf0.decodeAll(meta.payload)[0])
        assertEquals(1, meta.streamId)

        val audioHeader = nextMedia()
        assertArrayEquals(byteArrayOf(0xAF.toByte(), 0, 0x11, 0x90.toByte()), audioHeader.payload)

        val videoHeader = nextMedia()
        assertEquals(RtmpMessage.VIDEO, videoHeader.type)
        assertArrayEquals(Flv.avcSequenceHeader(sps, pps), videoHeader.payload)
        assertEquals(0L, videoHeader.timestamp)

        val key = nextMedia()
        assertEquals(0x17, key.payload[0].toInt())
        assertEquals(0L, key.timestamp)

        val inter = nextMedia()
        assertEquals(0x27, inter.payload[0].toInt())
        assertEquals(33L, inter.timestamp)

        val audio = nextMedia()
        assertEquals(RtmpMessage.AUDIO, audio.type)
        assertEquals(21L, audio.timestamp)
        assertArrayEquals(byteArrayOf(0xAF.toByte(), 1, 7, 8), audio.payload)

        streamer.stop()
        assertTrue(failures.isEmpty())
    }
}
