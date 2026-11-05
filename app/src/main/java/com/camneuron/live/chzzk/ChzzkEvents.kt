package com.camneuron.live.chzzk

import org.json.JSONException
import org.json.JSONObject

/** 채팅 세션으로 받는 것. */
sealed class ChzzkEvent {
    /** SYSTEM: connected(세션 키), subscribed/unsubscribed(이벤트 종류), revoked */
    data class System(val type: String, val sessionKey: String?, val eventType: String?) : ChzzkEvent()

    data class Chat(val nickname: String, val content: String, val role: String, val timeMs: Long) : ChzzkEvent()

    /** [type]: CHAT(채팅 후원) 또는 VIDEO(영상 후원). [amount]는 치즈 개수. */
    data class Donation(val nickname: String, val amount: Int, val text: String, val type: String) : ChzzkEvent()

    data class Subscription(val nickname: String, val tierName: String, val month: Int) : ChzzkEvent()

    companion object {
        /** Socket.IO 이벤트 하나를 해석한다. 모르는 이벤트나 깨진 데이터는 null. */
        fun parse(name: String, data: String?): ChzzkEvent? {
            val o = try {
                JSONObject(data ?: return null)
            } catch (e: JSONException) {
                return null
            }
            return when (name) {
                "SYSTEM" -> {
                    val d = o.optJSONObject("data")
                    System(o.optString("type"), d?.optString("sessionKey")?.ifEmpty { null }, d?.optString("eventType")?.ifEmpty { null })
                }
                "CHAT" -> Chat(
                    nickname = o.optJSONObject("profile")?.optString("nickname").orEmpty().ifEmpty { "익명" },
                    content = o.optString("content"),
                    role = o.optString("userRoleCode"),
                    timeMs = o.optLong("messageTime"),
                )
                "DONATION" -> Donation(
                    nickname = o.optString("donatorNickname").ifEmpty { "익명" },
                    amount = o.opt("payAmount")?.toString()?.toIntOrNull() ?: 0,
                    text = o.optString("donationText"),
                    type = o.optString("donationType"),
                )
                "SUBSCRIPTION" -> Subscription(
                    nickname = o.optString("subscriberNickname").ifEmpty { "익명" },
                    tierName = o.optString("tierName"),
                    month = o.opt("month")?.toString()?.toIntOrNull() ?: 0,
                )
                else -> null
            }
        }
    }
}
