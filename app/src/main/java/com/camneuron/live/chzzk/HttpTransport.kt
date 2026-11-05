package com.camneuron.live.chzzk

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP 요청 하나. 테스트에서는 가짜로 바꾼다. */
fun interface HttpTransport {
    class Response(val status: Int, val body: String)

    @Throws(IOException::class)
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): Response
}

/**
 * Android의 HttpURLConnection. 치지직 방송 설정 변경에 PATCH가 필요한데, Android 구현은 PATCH를 받는다
 * (PC 자바의 기본 구현은 받지 않아 단위 테스트는 가짜 [HttpTransport]를 쓴다).
 */
class UrlConnectionTransport(private val timeoutMs: Int = 10_000) : HttpTransport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpTransport.Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return HttpTransport.Response(status, text)
        } finally {
            connection.disconnect()
        }
    }
}
