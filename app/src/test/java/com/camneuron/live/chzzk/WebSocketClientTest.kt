package com.camneuron.live.chzzk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** PC 안에서 작은 웹소켓 서버를 띄워 클라이언트의 연결·프레임 처리를 본다. */
class WebSocketClientTest {
    private class Server(val acceptKey: (String) -> String = WebSocketClient::acceptKey) : AutoCloseable {
        val socket = ServerSocket(0)
        val port get() = socket.localPort
        lateinit var client: Socket
        lateinit var input: DataInputStream
        lateinit var output: DataOutputStream
        var requestLine = ""

        fun accept() {
            client = socket.accept()
            client.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            requestLine = reader.readLine()
            var key = ""
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
            }
            output = DataOutputStream(client.getOutputStream())
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            // BufferedReader가 앞서 읽어 둔 바이트가 없도록 클라이언트는 응답을 받은 뒤에야 프레임을 보낸다.
            input = DataInputStream(client.getInputStream())
        }

        /** 서버 프레임은 가리지 않는다. */
        fun sendFrame(opcode: Int, payload: ByteArray, fin: Boolean = true) {
            output.write((if (fin) 0x80 else 0) or opcode)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 0xFFFF -> {
                    output.write(126)
                    output.writeShort(payload.size)
                }
                else -> {
                    output.write(127)
                    output.writeLong(payload.size.toLong())
                }
            }
            output.write(payload)
            output.flush()
        }

        /** 클라이언트 프레임 하나 (가린 것을 풀어서). */
        fun readFrame(): Pair<Int, ByteArray> {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            assertTrue("client frames must be masked", b1 and 0x80 != 0)
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong() else if (length == 127L) length = input.readLong()
            val mask = ByteArray(4).also { input.readFully(it) }
            val payload = ByteArray(length.toInt()).also { input.readFully(it) }
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return (b0 and 0x0F) to payload
        }

        override fun close() {
            runCatching { client.close() }
            socket.close()
        }
    }

    @Test
    fun exchangesTextFramesAndAnswersPing() {
        Server().use { server ->
            val texts = LinkedBlockingQueue<String>()
            val closed = LinkedBlockingQueue<String>()
            val acceptor = Thread { server.accept() }.apply { start() }
            val ws = WebSocketClient.connect("ws://127.0.0.1:${server.port}/socket.io/?auth=T&EIO=3", { texts.offer(it) }, { closed.offer(it) })
            acceptor.join(5_000)
            assertEquals("GET /socket.io/?auth=T&EIO=3 HTTP/1.1", server.requestLine)

            ws.send("2")
            assertEquals(1 to "2", server.readFrame().let { it.first to it.second.toString(Charsets.UTF_8) })

            server.sendFrame(1, "0{\"pingInterval\":25000}".toByteArray())
            assertEquals("0{\"pingInterval\":25000}", texts.poll(5, TimeUnit.SECONDS))

            // 길이 126 이상 (16비트 길이), 한글, 조각난 메시지
            val long = "가".repeat(200)
            server.sendFrame(1, long.toByteArray())
            assertEquals(long, texts.poll(5, TimeUnit.SECONDS))
            server.sendFrame(1, "42[\"CH".toByteArray(), fin = false)
            server.sendFrame(0, "AT\"]".toByteArray())
            assertEquals("42[\"CHAT\"]", texts.poll(5, TimeUnit.SECONDS))

            server.sendFrame(9, "hi".toByteArray())
            val pong = server.readFrame()
            assertEquals(10, pong.first)
            assertEquals("hi", pong.second.toString(Charsets.UTF_8))

            server.sendFrame(8, byteArrayOf(0x03, 0xE8.toByte()))
            assertEquals("서버가 채팅 연결을 닫음", closed.poll(5, TimeUnit.SECONDS))
            assertEquals(8, server.readFrame().first)
        }
    }

    @Test
    fun clientCloseReportsOnce() {
        Server().use { server ->
            val closed = LinkedBlockingQueue<String>()
            val acceptor = Thread { server.accept() }.apply { start() }
            val ws = WebSocketClient.connect("ws://127.0.0.1:${server.port}/", {}, { closed.offer(it) })
            acceptor.join(5_000)
            ws.close()
            ws.close()
            assertEquals("closed", closed.poll(5, TimeUnit.SECONDS))
            assertEquals(8, server.readFrame().first)
            Thread.sleep(100)
            assertTrue(closed.isEmpty())
        }
    }

    @Test
    fun rejectsAWrongAcceptKey() {
        Server(acceptKey = { "wrong" }).use { server ->
            val acceptor = Thread { runCatching { server.accept() } }.apply { start() }
            try {
                WebSocketClient.connect("ws://127.0.0.1:${server.port}/", {}, {})
                fail()
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("accept"))
            }
            acceptor.join(5_000)
        }
    }

    @Test
    fun acceptKeyMatchesTheRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketClient.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }
}
