package com.camneuron.live.chzzk

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/**
 * 치지직 채팅 세션이 쓰는 Socket.IO 2.x (Engine.IO 3) 글자 패킷. 웹소켓 전송만 쓴다.
 *   Engine.IO: 0=열림(JSON), 2=ping(클라이언트가 보냄), 3=pong, 4=메시지
 *   Socket.IO (Engine.IO 메시지 안): 0=연결, 1=끊음, 2=이벤트(JSON 배열), 4=오류
 */
object SocketIo {
    sealed class Packet {
        data class Open(val pingIntervalMs: Long, val pingTimeoutMs: Long) : Packet()
        object Ping : Packet()
        object Pong : Packet()
        object Connect : Packet()
        object Disconnect : Packet()

        /** [data]는 이벤트의 첫 인자. 문자열이면 그대로(치지직은 JSON을 문자열로 보내기도 한다), 객체면 JSON 글자. */
        data class Event(val name: String, val data: String?) : Packet()
        data class Error(val message: String) : Packet()
        data class Other(val raw: String) : Packet()
    }

    const val PING = "2"

    /**
     * 세션 API가 준 주소(예: https://ssio08.nchat.naver.com:443?auth=TOKEN)를 웹소켓 주소로 바꾼다:
     * wss://ssio08.nchat.naver.com:443/socket.io/?auth=TOKEN&EIO=3&transport=websocket
     */
    fun websocketUrl(sessionUrl: String): String {
        val uri = URI(sessionUrl.trim())
        val scheme = when (uri.scheme?.lowercase()) {
            "https", "wss" -> "wss"
            "http", "ws" -> "ws"
            else -> throw IllegalArgumentException("세션 주소가 이상함: $sessionUrl")
        }
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val query = listOfNotNull(uri.rawQuery?.takeIf { it.isNotEmpty() }, "EIO=3", "transport=websocket").joinToString("&")
        return "$scheme://${uri.host}$port/socket.io/?$query"
    }

    fun parse(text: String): Packet {
        if (text.isEmpty()) return Packet.Other(text)
        return when (text[0]) {
            '0' -> parseOpen(text.substring(1))
            '2' -> Packet.Ping
            '3' -> Packet.Pong
            '4' -> parseMessage(text.substring(1))
            else -> Packet.Other(text)
        }
    }

    private fun parseOpen(json: String): Packet = try {
        val o = JSONObject(json)
        Packet.Open(o.optLong("pingInterval", 25_000), o.optLong("pingTimeout", 60_000))
    } catch (e: JSONException) {
        Packet.Open(25_000, 60_000)
    }

    private fun parseMessage(text: String): Packet {
        if (text.isEmpty()) return Packet.Other(text)
        var rest = text.substring(1)
        // 이름공간("/ns,")과 응답 번호(숫자)는 쓰지 않는다.
        if (rest.startsWith("/")) rest = rest.substringAfter(',', "")
        rest = rest.dropWhile { it.isDigit() }
        return when (text[0]) {
            '0' -> Packet.Connect
            '1' -> Packet.Disconnect
            '2' -> parseEvent(rest)
            '4' -> Packet.Error(rest)
            else -> Packet.Other(text)
        }
    }

    private fun parseEvent(json: String): Packet = try {
        val array = JSONArray(json)
        val name = array.optString(0)
        val data = when (val arg = array.opt(1)) {
            null, JSONObject.NULL -> null
            is String -> arg
            else -> arg.toString()
        }
        Packet.Event(name, data)
    } catch (e: JSONException) {
        Packet.Other("42$json")
    }
}
