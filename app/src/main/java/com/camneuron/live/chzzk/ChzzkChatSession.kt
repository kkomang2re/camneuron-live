package com.camneuron.live.chzzk

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 치지직 채팅 세션 하나를 붙여 두고 끊기면 다시 붙는다. 자기 스레드에서 돈다.
 *
 * 순서: 세션 주소 받기(API) → 웹소켓 연결 → Engine.IO 열림(ping 간격) → SYSTEM connected(세션 키)
 * → 채팅·후원·구독 구독(API) → 이벤트 수신. 세션 주소는 한 번만 쓸 수 있어 다시 붙을 때마다 새로 받는다.
 * 로그인이 없거나 만료되면 다시 시도하지 않고 멈춘다 ([Listener.onStatus]에 이유).
 *
 * 리스너는 이 클래스의 스레드나 웹소켓 읽기 스레드에서 불린다.
 */
class ChzzkChatSession(
    private val api: () -> ChzzkApi?,
    private val connector: WebSocketConnector,
    private val listener: Listener,
    private val retryDelaysMs: List<Long> = listOf(3_000, 6_000, 15_000, 30_000, 60_000),
    private val log: (String) -> Unit = {},
) {
    enum class State { CONNECTING, CONNECTED, RETRYING, STOPPED, NEEDS_LOGIN }

    data class Status(val state: State, val message: String? = null)

    interface Listener {
        fun onStatus(status: Status)
        fun onEvent(event: ChzzkEvent)
    }

    private val thread = Thread(::run, "chzzk-chat")
    private val wake = Object()

    @Volatile
    private var stopped = false

    @Volatile
    private var connection: WebSocketConnection? = null

    fun start() = thread.start()

    fun stop() {
        stopped = true
        synchronized(wake) { wake.notifyAll() }
        connection?.close()
        thread.interrupt()
    }

    private fun run() {
        var attempt = 0
        try {
            while (!stopped) {
                val client = api()
                if (client == null) {
                    listener.onStatus(Status(State.NEEDS_LOGIN, ChzzkApi.MSG_NOT_LOGGED_IN))
                    return
                }
                listener.onStatus(Status(State.CONNECTING))
                val startedAt = System.currentTimeMillis()
                val reason = try {
                    connectOnce(client)
                } catch (e: ChzzkApiException) {
                    if (e.status == 401) {
                        listener.onStatus(Status(State.NEEDS_LOGIN, e.message))
                        return
                    }
                    e.message ?: "채팅 연결 실패"
                } catch (e: InterruptedException) {
                    return
                } catch (e: Exception) {
                    "채팅 연결 실패 (${e.message ?: e.javaClass.simpleName})"
                }
                if (stopped) return
                // 1분 넘게 붙어 있었으면 처음 간격부터 다시 센다.
                if (System.currentTimeMillis() - startedAt > STABLE_MS) attempt = 0
                val delay = retryDelaysMs[attempt.coerceAtMost(retryDelaysMs.lastIndex)]
                attempt++
                log("chat disconnected: $reason, retry in ${delay}ms")
                listener.onStatus(Status(State.RETRYING, reason))
                synchronized(wake) { if (!stopped) wake.wait(delay) }
            }
        } catch (e: InterruptedException) {
            // stop()
        } finally {
            connection?.close()
            connection = null
            listener.onStatus(Status(State.STOPPED))
        }
    }

    /** 연결 하나가 끝날 때까지 돌고 끊긴 이유를 돌려준다. */
    private fun connectOnce(client: ChzzkApi): String {
        val sessionUrl = client.createSession()
        val closed = LinkedBlockingQueue<String>()
        val handler = PacketHandler(client)
        val ws = connector.connect(SocketIo.websocketUrl(sessionUrl), { handler.onText(it) }, { closed.offer(it) })
        connection = ws
        handler.connection = ws
        try {
            var lastPingMs = System.currentTimeMillis()
            while (!stopped) {
                // ping 간격은 열림 패킷이 와야 알 수 있어 자주 확인한다.
                val interval = handler.pingIntervalMs
                val reason = closed.poll(minOf(interval, CHECK_MS), TimeUnit.MILLISECONDS)
                if (reason != null) return reason
                val now = System.currentTimeMillis()
                if (now - handler.lastPongMs > interval + handler.pingTimeoutMs) {
                    ws.close()
                    return "채팅 서버가 응답하지 않음"
                }
                if (now - lastPingMs >= interval) {
                    lastPingMs = now
                    runCatching { ws.send(SocketIo.PING) }
                }
            }
            return "멈춤"
        } finally {
            ws.close()
            connection = null
        }
    }

    /** 웹소켓 읽기 스레드에서 받은 패킷을 처리한다. */
    private inner class PacketHandler(private val client: ChzzkApi) {
        @Volatile
        var connection: WebSocketConnection? = null

        @Volatile
        var pingIntervalMs = 25_000L

        @Volatile
        var pingTimeoutMs = 60_000L

        @Volatile
        var lastPongMs = System.currentTimeMillis()

        fun onText(text: String) {
            when (val packet = SocketIo.parse(text)) {
                is SocketIo.Packet.Open -> {
                    pingIntervalMs = packet.pingIntervalMs.coerceIn(1_000, 120_000)
                    pingTimeoutMs = packet.pingTimeoutMs.coerceIn(1_000, 300_000)
                    lastPongMs = System.currentTimeMillis()
                }
                SocketIo.Packet.Pong -> lastPongMs = System.currentTimeMillis()
                SocketIo.Packet.Ping -> runCatching { connection?.send("3") }
                is SocketIo.Packet.Event -> onEvent(packet)
                SocketIo.Packet.Disconnect -> connection?.close()
                is SocketIo.Packet.Error -> log("socket.io error: ${packet.message}")
                else -> Unit
            }
        }

        private fun onEvent(packet: SocketIo.Packet.Event) {
            val event = ChzzkEvent.parse(packet.name, packet.data) ?: return
            if (event is ChzzkEvent.System) {
                lastPongMs = System.currentTimeMillis()
                when (event.type) {
                    "connected" -> event.sessionKey?.let { key -> Thread({ subscribeAll(key) }, "chzzk-subscribe").start() }
                    "revoked" -> listener.onStatus(Status(State.RETRYING, "치지직이 구독을 취소함 (권한이 바뀌었을 수 있음)"))
                }
                return
            }
            listener.onEvent(event)
        }

        /** 채팅은 꼭 필요하고, 후원·구독은 권한이 없으면 건너뛴다. */
        private fun subscribeAll(sessionKey: String) {
            try {
                client.subscribe("chat", sessionKey)
            } catch (e: Exception) {
                log("chat subscribe failed: ${e.message}")
                listener.onStatus(Status(State.RETRYING, e.message ?: "채팅 구독 실패"))
                connection?.close()
                return
            }
            val missing = mutableListOf<String>()
            for ((event, label) in listOf("donation" to "후원", "subscription" to "구독")) {
                try {
                    client.subscribe(event, sessionKey)
                } catch (e: Exception) {
                    log("$event subscribe failed: ${e.message}")
                    missing += label
                }
            }
            val note = if (missing.isEmpty()) null else "${missing.joinToString("·")} 알림은 받지 못함 (앱 권한 확인)"
            listener.onStatus(Status(State.CONNECTED, note))
        }
    }

    private companion object {
        const val STABLE_MS = 60_000L
        const val CHECK_MS = 1_000L
    }
}
