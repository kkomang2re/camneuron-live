package com.camneuron.live.rtmp

/**
 * `rtmp://host[:port]/app[/더 깊은 경로]` 형식의 서버 주소. 방송 플랫폼은 이 주소와 스트림 키를 따로 준다.
 * [app]은 호스트 뒤 경로 전체다 (예: "relay", "live/abc"). [tcUrl]은 connect 명령에 그대로 넣는 주소.
 */
data class RtmpUrl(val host: String, val port: Int, val app: String) {
    val tcUrl: String
        get() {
            val h = if (':' in host) "[$host]" else host
            return if (port == DEFAULT_PORT) "rtmp://$h/$app" else "rtmp://$h:$port/$app"
        }

    companion object {
        const val DEFAULT_PORT = 1935

        /** 잘못된 주소면 null. rtmps(TLS)는 아직 지원하지 않는다. */
        fun parse(text: String): RtmpUrl? {
            val url = text.trim()
            if (!url.startsWith("rtmp://", ignoreCase = true)) return null
            val rest = url.substring(7)
            val slash = rest.indexOf('/')
            val authority = if (slash < 0) rest else rest.substring(0, slash)
            val app = if (slash < 0) "" else rest.substring(slash + 1).trimEnd('/')
            if (authority.isEmpty() || app.isEmpty()) return null
            val colon = authority.lastIndexOf(':')
            val host: String
            val port: Int
            if (colon >= 0 && !authority.endsWith("]")) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            } else {
                host = authority
                port = DEFAULT_PORT
            }
            if (host.isEmpty() || host.any { it.isWhitespace() }) return null
            return RtmpUrl(host.removePrefix("[").removeSuffix("]"), port, app)
        }
    }
}
