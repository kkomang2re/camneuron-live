package com.camneuron.live.chzzk

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** 웹소켓 연결 하나. 테스트에서는 가짜로 바꾼다. */
interface WebSocketConnection {
    fun send(text: String)
    fun close()
}

fun interface WebSocketConnector {
    /** [onText]·[onClosed]는 읽기 스레드에서 불린다. [onClosed]는 한 번만, 이유와 함께. */
    @Throws(IOException::class)
    fun connect(url: String, onText: (String) -> Unit, onClosed: (String) -> Unit): WebSocketConnection
}

/**
 * 글자 메시지만 쓰는 작은 웹소켓 클라이언트 (RFC 6455). 치지직 채팅 세션 하나에 충분하다.
 * wss는 시스템 TLS로 붙고 서버 이름(호스트명)을 확인한다. 보내는 프레임은 규칙대로 가린다(mask).
 */
class WebSocketClient private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    private val onText: (String) -> Unit,
    private val onClosed: (String) -> Unit,
) : WebSocketConnection {
    private val random = SecureRandom()
    private val writeLock = Any()

    @Volatile
    private var closed = false

    private val reader = Thread(::readLoop, "chzzk-websocket")

    override fun send(text: String) = sendFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

    override fun close() {
        if (closed) return
        runCatching { sendFrame(OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())) } // 1000 정상 종료
        finish("closed")
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write(payload.size shr 8)
                header.write(payload.size)
            }
            else -> {
                header.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) header.write((payload.size.toLong() shr shift).toInt())
            }
        }
        header.write(mask)
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        synchronized(writeLock) {
            if (closed) throw IOException("websocket closed")
            output.write(header.toByteArray())
            output.write(masked)
            output.flush()
        }
    }

    private fun readLoop() {
        var reason = "연결이 끊김"
        try {
            val message = ByteArrayOutputStream()
            var messageOp = -1
            while (!closed) {
                val b0 = input.readUnsignedByte()
                val b1 = input.readUnsignedByte()
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong()
                else if (length == 127L) length = input.readLong()
                if (length < 0 || length > MAX_MESSAGE) throw IOException("frame too large: $length")
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
                val payload = ByteArray(length.toInt()).also { input.readFully(it) }
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    OP_TEXT, OP_BINARY, OP_CONTINUATION -> {
                        if (opcode != OP_CONTINUATION) {
                            message.reset()
                            messageOp = opcode
                        }
                        message.write(payload)
                        if (message.size() > MAX_MESSAGE) throw IOException("message too large")
                        if (fin && messageOp == OP_TEXT) onText(message.toString("UTF-8"))
                    }
                    OP_PING -> sendFrame(OP_PONG, payload)
                    OP_PONG -> Unit
                    OP_CLOSE -> {
                        reason = "서버가 채팅 연결을 닫음"
                        runCatching { sendFrame(OP_CLOSE, payload.take(2).toByteArray()) }
                        break
                    }
                }
            }
        } catch (e: EOFException) {
            reason = "서버가 채팅 연결을 닫음"
        } catch (e: IOException) {
            if (!closed) reason = "채팅 연결이 끊김 (${e.message ?: e.javaClass.simpleName})"
        }
        finish(reason)
    }

    @Synchronized
    private fun finish(reason: String) {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        onClosed(reason)
    }

    companion object : WebSocketConnector {
        private const val OP_CONTINUATION = 0
        private const val OP_TEXT = 1
        private const val OP_BINARY = 2
        private const val OP_CLOSE = 8
        private const val OP_PING = 9
        private const val OP_PONG = 10
        private const val MAX_MESSAGE = 1L shl 20
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        override fun connect(url: String, onText: (String) -> Unit, onClosed: (String) -> Unit): WebSocketConnection {
            val uri = URI(url)
            val secure = when (uri.scheme?.lowercase()) {
                "wss" -> true
                "ws" -> false
                else -> throw IOException("not a websocket url")
            }
            val host = uri.host ?: throw IOException("no host")
            val port = if (uri.port >= 0) uri.port else if (secure) 443 else 80
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

            val plain = Socket()
            val socket: Socket = try {
                plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                if (secure) {
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
                    // 서버 인증서가 이 호스트의 것인지 확인한다 (SSLSocket은 기본으로 하지 않는다).
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.startHandshake()
                    tls
                } else {
                    plain
                }
            } catch (e: IOException) {
                runCatching { plain.close() }
                throw e
            }
            try {
                socket.soTimeout = 0
                socket.tcpNoDelay = true
                val output = socket.getOutputStream()
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
                val hostHeader = if (uri.port >= 0) "$host:$port" else host
                val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $hostHeader\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "User-Agent: CamNeuronLive\r\n\r\n"
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.flush()
                socket.soTimeout = CONNECT_TIMEOUT_MS
                val headers = readHeaders(input)
                val status = headers.firstOrNull().orEmpty()
                if (!status.contains(" 101")) throw IOException("websocket upgrade refused: $status")
                val accept = headers.drop(1).firstOrNull { it.startsWith("sec-websocket-accept:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
                if (accept != acceptKey(key)) throw IOException("bad websocket accept key")
                socket.soTimeout = 0
                return WebSocketClient(socket, input, output, onText, onClosed).also { it.reader.start() }
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }

        fun acceptKey(key: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))

        private fun readHeaders(input: InputStream): List<String> {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) throw EOFException("handshake cut")
                if (c == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    if (text.isEmpty()) return lines
                    lines += text
                    line.setLength(0)
                    if (lines.size > 100) throw IOException("too many headers")
                } else {
                    line.append(c.toChar())
                    if (line.length > 8192) throw IOException("header too long")
                }
            }
        }
    }
}
