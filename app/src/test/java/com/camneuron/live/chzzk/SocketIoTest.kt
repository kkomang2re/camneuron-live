package com.camneuron.live.chzzk

import com.camneuron.live.chzzk.SocketIo.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketIoTest {
    @Test
    fun sessionUrlBecomesWebsocketUrl() {
        assertEquals(
            "wss://ssio08.nchat.naver.com:443/socket.io/?auth=TOKEN&EIO=3&transport=websocket",
            SocketIo.websocketUrl("https://ssio08.nchat.naver.com:443?auth=TOKEN"),
        )
        assertEquals("ws://127.0.0.1:9000/socket.io/?EIO=3&transport=websocket", SocketIo.websocketUrl("http://127.0.0.1:9000"))
    }

    @Test
    fun engineIoPackets() {
        assertEquals(Packet.Open(25_000, 60_000), SocketIo.parse("""0{"sid":"x","upgrades":[],"pingInterval":25000,"pingTimeout":60000}"""))
        assertEquals(Packet.Ping, SocketIo.parse("2"))
        assertEquals(Packet.Pong, SocketIo.parse("3"))
        assertEquals(Packet.Connect, SocketIo.parse("40"))
        assertEquals(Packet.Disconnect, SocketIo.parse("41"))
        assertTrue(SocketIo.parse("44\"bad\"") is Packet.Error)
        assertTrue(SocketIo.parse("6") is Packet.Other)
        assertTrue(SocketIo.parse("") is Packet.Other)
    }

    @Test
    fun eventWithStringData() {
        // 치지직은 이벤트 데이터를 JSON 문자열로 보내기도 한다.
        val packet = SocketIo.parse("""42["SYSTEM","{\"type\":\"connected\",\"data\":{\"sessionKey\":\"k1\"}}"]""")
        assertEquals(Packet.Event("SYSTEM", """{"type":"connected","data":{"sessionKey":"k1"}}"""), packet)
    }

    @Test
    fun eventWithObjectDataNamespaceAndAckId() {
        val packet = SocketIo.parse("""42/chat,7["CHAT",{"content":"hi"}]""") as Packet.Event
        assertEquals("CHAT", packet.name)
        assertEquals("hi", org.json.JSONObject(packet.data!!).getString("content"))
        assertEquals(Packet.Event("PING", null), SocketIo.parse("""42["PING"]"""))
        assertTrue(SocketIo.parse("42[broken") is Packet.Other)
    }
}
