package com.camneuron.live.chzzk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ChzzkChatSessionTest {
    private val transport = FakeTransport()
    private val store = MemoryTokenStore(ChzzkTokens("access", "refresh", System.currentTimeMillis() + 3_600_000))
    private val api = ChzzkApi(transport, "id", "secret", store, FakeTransport.BASE)

    /** 연결마다 하나. 테스트가 서버 쪽 글자를 밀어 넣고, 앱이 보낸 글자를 본다. */
    private class FakeConnection(val url: String, val onText: (String) -> Unit, val onClosed: (String) -> Unit) : WebSocketConnection {
        val sent = CopyOnWriteArrayList<String>()

        @Volatile
        var closed = false

        override fun send(text: String) {
            sent += text
        }

        override fun close() {
            if (closed) return
            closed = true
            onClosed("closed by app")
        }
    }

    private val connections = LinkedBlockingQueue<FakeConnection>()
    private val connector = WebSocketConnector { url, onText, onClosed -> FakeConnection(url, onText, onClosed).also { connections.offer(it) } }
    private val statuses = LinkedBlockingQueue<ChzzkChatSession.Status>()
    private val events = LinkedBlockingQueue<ChzzkEvent>()
    private val listener = object : ChzzkChatSession.Listener {
        override fun onStatus(status: ChzzkChatSession.Status) {
            statuses.offer(status)
        }

        override fun onEvent(event: ChzzkEvent) {
            events.offer(event)
        }
    }

    private fun session(currentApi: () -> ChzzkApi? = { api }) =
        ChzzkChatSession(currentApi, connector, listener, retryDelaysMs = listOf(50))

    private fun waitFor(state: ChzzkChatSession.State): ChzzkChatSession.Status {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val status = statuses.poll(left.coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: throw AssertionError("no $state status")
            if (status.state == state) return status
        }
    }

    private fun nextConnection() = connections.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no connection")

    @Test
    fun connectsSubscribesAndDeliversEvents() {
        transport.ok("GET", "/open/v1/sessions/auth", """{"url":"https://ssio01.test:443?auth=T1"}""")
        transport.ok("POST", "/open/v1/sessions/events/subscribe/chat")
        transport.ok("POST", "/open/v1/sessions/events/subscribe/donation")
        transport.on("POST", "/open/v1/sessions/events/subscribe/subscription", 403, """{"code":403,"message":"FORBIDDEN"}""")
        val chat = session()
        chat.start()
        try {
            val ws = nextConnection()
            assertEquals("wss://ssio01.test:443/socket.io/?auth=T1&EIO=3&transport=websocket", ws.url)
            ws.onText("""0{"sid":"s","pingInterval":100,"pingTimeout":5000}""")
            ws.onText("40")
            ws.onText("""42["SYSTEM","{\"type\":\"connected\",\"data\":{\"sessionKey\":\"KEY\"}}"]""")
            val connected = waitFor(ChzzkChatSession.State.CONNECTED)
            assertTrue(connected.message!!.contains("구독"))
            assertTrue(transport.calls("POST", "/open/v1/sessions/events/subscribe/chat").single().url.contains("sessionKey=KEY"))

            ws.onText("""42["CHAT","{\"profile\":{\"nickname\":\"산책중\"},\"content\":\"안녕\",\"messageTime\":1}"]""")
            ws.onText("""42["DONATION",{"donatorNickname":"치즈러버","payAmount":"500","donationText":"","donationType":"CHAT"}]""")
            assertEquals(ChzzkEvent.Chat("산책중", "안녕", "", 1), events.poll(5, TimeUnit.SECONDS))
            assertEquals(ChzzkEvent.Donation("치즈러버", 500, "", "CHAT"), events.poll(5, TimeUnit.SECONDS))

            // Engine.IO 3: 클라이언트가 ping("2")을 보낸다 (열림 패킷의 간격으로, 늦어도 1초 안에 알아챈다).
            val deadline = System.currentTimeMillis() + 3_000
            while (!ws.sent.contains("2") && System.currentTimeMillis() < deadline) Thread.sleep(20)
            ws.onText("3")
            assertTrue(ws.sent.contains("2"))
        } finally {
            chat.stop()
        }
        waitFor(ChzzkChatSession.State.STOPPED)
    }

    @Test
    fun reconnectsWithANewSessionAfterADrop() {
        transport.ok("GET", "/open/v1/sessions/auth", """{"url":"https://ssio01.test?auth=T"}""")
        val chat = session()
        chat.start()
        try {
            val first = nextConnection()
            first.onClosed("서버가 채팅 연결을 닫음")
            assertEquals("서버가 채팅 연결을 닫음", waitFor(ChzzkChatSession.State.RETRYING).message)
            nextConnection()
            assertEquals(2, transport.calls("GET", "/open/v1/sessions/auth").size)
        } finally {
            chat.stop()
        }
    }

    @Test
    fun stopsWhenLoginIsGone() {
        transport.on("GET", "/open/v1/sessions/auth", 401, """{"code":401,"message":"INVALID_TOKEN"}""")
        transport.on("POST", "/auth/v1/token", 401, """{"code":401,"message":"INVALID_TOKEN"}""")
        val chat = session()
        chat.start()
        assertEquals(ChzzkApi.MSG_LOGIN_EXPIRED, waitFor(ChzzkChatSession.State.NEEDS_LOGIN).message)
        waitFor(ChzzkChatSession.State.STOPPED)
        assertTrue(connections.isEmpty())
    }

    @Test
    fun noAccountMeansNeedsLogin() {
        val chat = session { null }
        chat.start()
        waitFor(ChzzkChatSession.State.NEEDS_LOGIN)
        waitFor(ChzzkChatSession.State.STOPPED)
    }
}
