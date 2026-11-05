package com.camneuron.live.chzzk

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** 로그인 토큰. [expiresAtMs]는 System.currentTimeMillis 기준. */
data class ChzzkTokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long)

/** 토큰을 보관하는 곳 (폰에서는 앱 전용 저장소). */
interface ChzzkTokenStore {
    var tokens: ChzzkTokens?
}

data class ChzzkChannel(val channelId: String, val channelName: String)

data class ChzzkCategory(val type: String, val id: String, val name: String)

data class ChzzkLiveSetting(val title: String, val category: ChzzkCategory?, val tags: List<String>)

/** 채팅 설정 중 이 앱이 바꾸는 것. 나머지 값은 읽은 그대로 돌려보낸다. */
data class ChzzkChatSettings(val raw: JSONObject) {
    val slowModeSec: Int get() = raw.optInt("chatSlowModeSec", 0)
    val followerOnly: Boolean get() = raw.optString("chatAvailableGroup", "ALL") == "FOLLOWER"
}

/** 치지직 API가 거부함. [message]는 화면에 그대로 보인다. [serverMessage]는 치지직이 보낸 원래 글자 (예: INVALID_CLIENT). */
class ChzzkApiException(val status: Int, val code: String?, message: String, val serverMessage: String? = null) : IOException(message)

/**
 * 치지직 Open API (https://chzzk.gitbook.io/chzzk). 네트워크를 쓰므로 메인 스레드에서 부르지 않는다.
 *
 * 사용자 API는 Access Token으로 부르고, 만료됐거나(401) 곧 만료되면 Refresh Token으로 한 번 새로 받은 뒤 다시 부른다.
 * Refresh Token은 한 번만 쓸 수 있어 새로 받기는 한 번에 하나만 한다.
 */
class ChzzkApi(
    private val transport: HttpTransport,
    private val clientId: String,
    private val clientSecret: String,
    private val store: ChzzkTokenStore,
    private val baseUrl: String = BASE_URL,
    /** 실패한 요청의 경로·상태·치지직 메시지만 남긴다 (본문과 토큰은 남기지 않는다). */
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val refreshLock = Any()

    // ------------------------------------------------------------------------------------------
    // 로그인

    fun exchangeCode(code: String, state: String): ChzzkTokens {
        val body = JSONObject()
            .put("grantType", "authorization_code")
            .put("clientId", clientId)
            .put("clientSecret", clientSecret)
            .put("code", code)
            .put("state", state)
        val response = try {
            call("POST", "/auth/v1/token", jsonHeaders(), body.toString())
        } catch (e: ChzzkApiException) {
            // 로그인 마무리 실패는 "만료"가 아니라 앱 정보·코드 문제다. 치지직이 보낸 이유를 그대로 보인다.
            val reason = when {
                e.serverMessage.orEmpty().contains("INVALID_CLIENT", ignoreCase = true) -> MSG_BAD_CLIENT
                e.status == 0 -> e.message ?: "연결 실패"
                else -> "치지직이 토큰을 주지 않음 (HTTP ${e.status}${e.serverMessage?.let { " $it" }.orEmpty()})"
            }
            throw ChzzkApiException(e.status, e.code, reason, e.serverMessage)
        }
        val tokens = parseTokens(response)
        store.tokens = tokens
        return tokens
    }

    /** 로그아웃: 이 앱으로 받은 토큰을 모두 폐기한다. 실패해도 폰의 토큰은 지운다. */
    fun revoke() {
        val tokens = store.tokens ?: return
        store.tokens = null
        val body = JSONObject()
            .put("clientId", clientId)
            .put("clientSecret", clientSecret)
            .put("token", tokens.refreshToken)
            .put("tokenTypeHint", "refresh_token")
        runCatching { call("POST", "/auth/v1/token/revoke", jsonHeaders(), body.toString()) }
    }

    // ------------------------------------------------------------------------------------------
    // 사용자 API

    fun me(): ChzzkChannel {
        val o = userCall("GET", "/open/v1/users/me")
        return ChzzkChannel(o.optString("channelId"), o.optString("channelName"))
    }

    fun streamKey(): String = userCall("GET", "/open/v1/streams/key").optString("streamKey")

    fun liveSetting(): ChzzkLiveSetting {
        val o = userCall("GET", "/open/v1/lives/setting")
        val c = o.optJSONObject("category")
        val category = c?.takeIf { it.optString("categoryId").isNotEmpty() }?.let {
            ChzzkCategory(it.optString("categoryType"), it.optString("categoryId"), it.optString("categoryValue"))
        }
        return ChzzkLiveSetting(o.optString("defaultLiveTitle"), category, strings(o.optJSONArray("tags")))
    }

    /**
     * 방송 제목·카테고리·태그를 바꾼다. null인 항목은 보내지 않는다 (그대로 둔다).
     * [category]가 [NO_CATEGORY]면 카테고리를 지운다. 태그는 빈 목록이면 모두 지운다.
     */
    fun updateLiveSetting(title: String? = null, category: ChzzkCategory? = null, tags: List<String>? = null) {
        val body = JSONObject()
        if (title != null) body.put("defaultLiveTitle", title)
        if (category != null) {
            if (category == NO_CATEGORY) {
                body.put("categoryId", "")
            } else {
                body.put("categoryType", category.type).put("categoryId", category.id)
            }
        }
        if (tags != null) body.put("tags", JSONArray(tags))
        userCall("PATCH", "/open/v1/lives/setting", body)
    }

    /** 채팅 소켓 주소 (한 번만 쓸 수 있고 잠깐만 유효하다). */
    fun createSession(): String = userCall("GET", "/open/v1/sessions/auth").optString("url")

    /** [event]: chat, donation, subscription */
    fun subscribe(event: String, sessionKey: String) {
        // 문서가 sessionKey를 쿼리로 받는지 본문으로 받는지 밝히지 않아 둘 다 보낸다.
        userCall("POST", "/open/v1/sessions/events/subscribe/$event?sessionKey=${enc(sessionKey)}", JSONObject().put("sessionKey", sessionKey))
    }

    fun sendChat(message: String) {
        userCall("POST", "/open/v1/chats/send", JSONObject().put("message", message.take(MAX_CHAT_LENGTH)))
    }

    fun sendNotice(message: String) {
        userCall("POST", "/open/v1/chats/notice", JSONObject().put("message", message.take(MAX_CHAT_LENGTH)))
    }

    fun chatSettings(): ChzzkChatSettings = ChzzkChatSettings(userCall("GET", "/open/v1/chats/settings"))

    /** 읽은 설정에서 슬로우 모드·팔로워 전용만 바꿔 돌려보낸다. */
    fun updateChatSettings(current: ChzzkChatSettings, slowModeSec: Int? = null, followerOnly: Boolean? = null) {
        val body = JSONObject(current.raw.toString())
        if (slowModeSec != null) body.put("chatSlowModeSec", slowModeSec)
        if (followerOnly != null) body.put("chatAvailableGroup", if (followerOnly) "FOLLOWER" else "ALL")
        userCall("PUT", "/open/v1/chats/settings", body)
    }

    // ------------------------------------------------------------------------------------------
    // 앱 인증 API (로그인 없이 Client ID/Secret으로)

    fun searchCategories(query: String, size: Int = 20): List<ChzzkCategory> {
        val headers = mapOf("Client-Id" to clientId, "Client-Secret" to clientSecret, "Content-Type" to "application/json")
        val o = call("GET", "/open/v1/categories/search?size=${size.coerceIn(1, 50)}&query=${enc(query)}", headers, null)
        val list = o.optJSONArray("data") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { ChzzkCategory(it.optString("categoryType"), it.optString("categoryId"), it.optString("categoryValue")) }
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun userCall(method: String, path: String, body: JSONObject? = null): JSONObject {
        val token = validAccessToken()
        try {
            return call(method, path, bearer(token), body?.toString())
        } catch (e: ChzzkApiException) {
            if (e.status != 401) throw e
        }
        // 401: 토큰이 막 만료됐거나 폐기됨. 한 번만 새로 받아 다시 부른다.
        val fresh = refresh(token)
        return call(method, path, bearer(fresh), body?.toString())
    }

    private fun validAccessToken(): String {
        val tokens = store.tokens ?: throw ChzzkApiException(401, "NOT_LOGGED_IN", MSG_NOT_LOGGED_IN)
        if (tokens.expiresAtMs - now() > REFRESH_MARGIN_MS) return tokens.accessToken
        return refresh(tokens.accessToken)
    }

    /** [staleAccess]를 쓰다 실패한 경우에만 새로 받는다. 다른 스레드가 이미 새로 받았으면 그 토큰을 쓴다. */
    private fun refresh(staleAccess: String): String {
        synchronized(refreshLock) { return refreshLocked(staleAccess) }
    }

    private fun refreshLocked(staleAccess: String): String {
        val tokens = store.tokens ?: throw ChzzkApiException(401, "NOT_LOGGED_IN", MSG_NOT_LOGGED_IN)
        if (tokens.accessToken != staleAccess && tokens.expiresAtMs - now() > REFRESH_MARGIN_MS) return tokens.accessToken
        val body = JSONObject()
            .put("grantType", "refresh_token")
            .put("refreshToken", tokens.refreshToken)
            .put("clientId", clientId)
            .put("clientSecret", clientSecret)
        val fresh = try {
            parseTokens(call("POST", "/auth/v1/token", jsonHeaders(), body.toString()))
        } catch (e: ChzzkApiException) {
            if (e.status == 400 || e.status == 401) {
                store.tokens = null // Refresh Token도 만료·폐기됨: 다시 로그인해야 한다
                throw ChzzkApiException(401, e.code, MSG_LOGIN_EXPIRED)
            }
            throw e
        }
        store.tokens = fresh
        return fresh.accessToken
    }

    private fun parseTokens(o: JSONObject): ChzzkTokens {
        val access = o.optString("accessToken")
        val refresh = o.optString("refreshToken")
        if (access.isEmpty() || refresh.isEmpty()) throw ChzzkApiException(500, null, "치지직이 토큰을 주지 않음")
        // expiresIn은 문서에 String("86400")으로 나와 있다. 숫자로 와도 읽는다.
        val seconds = o.opt("expiresIn")?.toString()?.toLongOrNull() ?: DEFAULT_EXPIRES_S
        return ChzzkTokens(access, refresh, now() + seconds * 1000)
    }

    /** 요청을 보내고 응답의 content(없으면 전체)를 돌려준다. 실패면 [ChzzkApiException]. */
    private fun call(method: String, path: String, headers: Map<String, String>, body: String?): JSONObject {
        val response = try {
            transport.request(method, baseUrl + path, headers, body)
        } catch (e: IOException) {
            throw ChzzkApiException(0, null, "치지직에 연결하지 못함 (${e.message ?: e.javaClass.simpleName})")
        }
        val json = try {
            if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
        } catch (e: JSONException) {
            if (response.status in 200..299) throw ChzzkApiException(response.status, null, "치지직 응답을 읽지 못함")
            JSONObject()
        }
        val code = json.opt("code")
        val codeNumber = code?.toString()?.toIntOrNull()
        val ok = response.status in 200..299 && (code == null || codeNumber == 200)
        if (!ok) {
            val status = if (response.status in 200..299) codeNumber ?: response.status else response.status
            val serverMessage = json.optString("message").takeIf { it.isNotBlank() && it != "null" }
            log("$method ${path.substringBefore('?')} failed: HTTP ${response.status}, code=$code, message=$serverMessage")
            throw ChzzkApiException(status, code?.toString(), errorMessage(status, serverMessage), serverMessage)
        }
        return json.optJSONObject("content") ?: json
    }

    private fun jsonHeaders() = mapOf("Content-Type" to "application/json")

    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json")

    companion object {
        const val BASE_URL = "https://openapi.chzzk.naver.com"
        const val MAX_CHAT_LENGTH = 100

        /** [updateLiveSetting]에 주면 카테고리를 지운다. */
        val NO_CATEGORY = ChzzkCategory("", "", "")

        private const val REFRESH_MARGIN_MS = 5 * 60_000L
        private const val DEFAULT_EXPIRES_S = 86_400L
        const val MSG_NOT_LOGGED_IN = "치지직 계정이 연결되지 않음 · 방송 설정에서 로그인하세요"
        const val MSG_LOGIN_EXPIRED = "치지직 로그인이 만료됨 · 방송 설정에서 다시 로그인하세요"

        const val MSG_BAD_CLIENT = "Client ID 또는 Client Secret이 맞지 않음 · 개발자 센터의 값과 다시 비교하세요"

        fun errorMessage(status: Int, serverMessage: String?): String {
            val detail = serverMessage?.takeIf { it.isNotBlank() && it != "null" }?.let { " ($it)" }.orEmpty()
            return when (status) {
                401 -> when {
                    serverMessage.orEmpty().contains("INVALID_CLIENT", ignoreCase = true) -> MSG_BAD_CLIENT
                    serverMessage.isNullOrBlank() || serverMessage.contains("TOKEN", ignoreCase = true) -> MSG_LOGIN_EXPIRED
                    else -> "치지직이 거부함$detail"
                }
                403 -> "권한이 없음 · 개발자 센터의 앱 권한(scope)을 확인하세요$detail"
                404 -> "찾을 수 없음$detail"
                429 -> "요청이 너무 많음 · 잠시 후 다시 시도하세요"
                else -> "치지직 API 오류 $status$detail"
            }
        }

        private fun strings(array: JSONArray?): List<String> =
            if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
