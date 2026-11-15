package com.camneuron.live.chzzk

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * 치지직이 지금 이 채널의 방송을 내보내고 있는지([open])와 시청자 수([viewers]).
 *
 * Open API에는 내 방송의 시청자 수가 없어, 치지직 웹 플레이어가 쓰는 공개 주소를 읽는다 (로그인·토큰 없이 된다).
 * 문서에 없는 주소라 치지직이 바꾸면 읽지 못할 수 있다. 그때는 null이고, 화면에서 그 줄만 "확인 못 함"이 된다
 * (방송 자체와는 상관없다). 혼자 쓰는 앱이라 쓴다.
 */
data class ChzzkLiveStatus(val open: Boolean, val viewers: Int) {
    companion object {
        const val BASE_URL = "https://api.chzzk.naver.com"

        private val CHANNEL_ID = Regex("[0-9A-Za-z]{8,64}")

        fun path(channelId: String): String = "/polling/v2/channels/$channelId/live-status"

        /** 응답 본문을 해석한다. 모양이 다르면 null. */
        fun parse(body: String): ChzzkLiveStatus? {
            val content = try {
                JSONObject(body).optJSONObject("content")
            } catch (e: JSONException) {
                null
            } ?: return null
            val status = content.optString("status")
            if (status.isEmpty()) return null
            return ChzzkLiveStatus(open = status == "OPEN", viewers = content.optInt("concurrentUserCount", 0).coerceAtLeast(0))
        }

        /** 네트워크를 쓴다 (메인 스레드에서 부르지 않는다). 실패하면 null. */
        fun fetch(transport: HttpTransport, channelId: String, baseUrl: String = BASE_URL): ChzzkLiveStatus? {
            if (!CHANNEL_ID.matches(channelId)) return null
            val response = try {
                transport.request("GET", baseUrl + path(channelId), mapOf("Accept" to "application/json"), null)
            } catch (e: IOException) {
                return null
            }
            if (response.status !in 200..299) return null
            return parse(response.body)
        }
    }
}
