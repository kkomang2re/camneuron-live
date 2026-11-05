package com.camneuron.live.chzzk

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * "ID·Secret 확인"에서 같은 ID·Secret으로 요청 모양만 바꿔 여러 번 보내, 어떤 차이 때문에 치지직이 거절하는지 찾는다.
 * PC의 curl은 통과하는데 앱은 INVALID_CLIENT를 받는 경우를 가리기 위한 것. 값은 로그에 남기지 않는다 (길이와 지문만).
 */
object ChzzkDiagnose {
    private const val TAG = "ChzzkDiagnose"

    data class Variant(val name: String, val query: String, val headers: Map<String, String>)

    fun variants(clientId: String, clientSecret: String): List<Variant> {
        val auth = mapOf("Client-Id" to clientId, "Client-Secret" to clientSecret)
        val korean = "size=1&query=%EA%B2%8C%EC%9E%84"
        val english = "query=game&size=1"
        return listOf(
            Variant("앱 그대로", korean, auth + ("Content-Type" to "application/json")),
            Variant("Content-Type 없이", korean, auth),
            Variant("영어 검색어", english, auth),
            Variant(
                "curl과 같게", english,
                auth + mapOf("User-Agent" to "curl/8.9.1", "Accept" to "*/*", "Accept-Encoding" to "identity"),
            ),
        )
    }

    /** 각 모양의 결과 한 줄씩 (예: "Content-Type 없이: 200"). 네트워크를 쓰므로 백그라운드에서. */
    fun run(clientId: String, clientSecret: String): List<String> {
        Log.i(TAG, "client id len=${clientId.length} fp=${fingerprint(clientId)}, secret len=${clientSecret.length} fp=${fingerprint(clientSecret)}")
        return variants(clientId, clientSecret).map { variant ->
            val result = try {
                val connection = URL("${ChzzkApi.BASE_URL}/open/v1/categories/search?${variant.query}").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    variant.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                    val status = connection.responseCode
                    val body = (if (status >= 400) connection.errorStream else connection.inputStream)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    val message = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                    if (message != null) "$status $message" else "$status"
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                "오류 ${e.javaClass.simpleName}"
            }
            "${variant.name}: $result".also { Log.i(TAG, it) }
        }
    }

    /** SHA-256 앞 16자리. 값을 드러내지 않고 PC 쪽 값과 같은지 비교하는 데 쓴다. */
    fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
}
