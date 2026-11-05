package com.camneuron.live.chzzk

import java.io.IOException

/** 요청을 기록하고, "METHOD 경로(쿼리 제외)"마다 정해 둔 응답을 차례로 돌려준다. */
class FakeTransport : HttpTransport {
    data class Request(val method: String, val url: String, val headers: Map<String, String>, val body: String?) {
        val path: String get() = url.removePrefix(BASE).substringBefore('?')
    }

    val requests = mutableListOf<Request>()
    private val responses = mutableMapOf<String, ArrayDeque<HttpTransport.Response>>()

    fun on(method: String, path: String, status: Int, body: String) {
        responses.getOrPut("$method $path") { ArrayDeque() }.addLast(HttpTransport.Response(status, body))
    }

    /** 성공 응답 ({code:200, content:...}) */
    fun ok(method: String, path: String, content: String = "{}") = on(method, path, 200, """{"code":200,"message":null,"content":$content}""")

    fun calls(method: String, path: String) = requests.filter { it.method == method && it.path == path }

    @Synchronized
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpTransport.Response {
        val request = Request(method, url, headers, body)
        requests += request
        val queue = responses["$method ${request.path}"] ?: throw IOException("no response for $method ${request.path}")
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    companion object {
        const val BASE = "https://api.test"
    }
}

class MemoryTokenStore(override var tokens: ChzzkTokens? = null) : ChzzkTokenStore
