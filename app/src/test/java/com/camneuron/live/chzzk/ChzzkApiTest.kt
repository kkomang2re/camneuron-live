package com.camneuron.live.chzzk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChzzkApiTest {
    private val transport = FakeTransport()
    private var clock = 1_000_000L
    private val valid = ChzzkTokens("access1", "refresh1", clock + 3_600_000)

    private fun api(tokens: ChzzkTokens? = valid, store: MemoryTokenStore = MemoryTokenStore(tokens)) =
        ChzzkApi(transport, "id", "secret", store, FakeTransport.BASE) { clock }

    private fun body(request: FakeTransport.Request) = JSONObject(request.body!!)

    @Test
    fun exchangeCodeStoresTokens() {
        transport.ok("POST", "/auth/v1/token", """{"accessToken":"a","refreshToken":"r","tokenType":"Bearer","expiresIn":"86400"}""")
        val store = MemoryTokenStore()
        api(store = store).exchangeCode("code1", "state1")
        assertEquals(ChzzkTokens("a", "r", clock + 86_400_000), store.tokens)
        val sent = body(transport.requests.single())
        assertEquals("authorization_code", sent.getString("grantType"))
        assertEquals("id", sent.getString("clientId"))
        assertEquals("secret", sent.getString("clientSecret"))
        assertEquals("code1", sent.getString("code"))
        assertEquals("state1", sent.getString("state"))
    }

    @Test
    fun loginFailureSaysWhy() {
        transport.on("POST", "/auth/v1/token", 401, """{"code":401,"message":"INVALID_CLIENT"}""")
        try {
            api(tokens = null).exchangeCode("c", "s")
            fail()
        } catch (e: ChzzkApiException) {
            assertEquals(ChzzkApi.MSG_BAD_CLIENT, e.message)
        }
        transport.on("POST", "/auth/v1/token", 400, """{"code":400,"message":"INVALID_REQUEST"}""")
        val other = FakeTransport().apply { on("POST", "/auth/v1/token", 400, """{"code":400,"message":"INVALID_REQUEST"}""") }
        try {
            ChzzkApi(other, "id", "secret", MemoryTokenStore(), FakeTransport.BASE) { clock }.exchangeCode("c", "s")
            fail()
        } catch (e: ChzzkApiException) {
            assertEquals("치지직이 토큰을 주지 않음 (HTTP 400 INVALID_REQUEST)", e.message)
        }
    }

    @Test
    fun userCallsSendBearerAndReadContent() {
        transport.ok("GET", "/open/v1/users/me", """{"channelId":"ch1","channelName":"내 채널"}""")
        assertEquals(ChzzkChannel("ch1", "내 채널"), api().me())
        assertEquals("Bearer access1", transport.requests.single().headers["Authorization"])
    }

    @Test
    fun refreshesOnceAfter401AndRetries() {
        transport.on("GET", "/open/v1/streams/key", 401, """{"code":401,"message":"INVALID_TOKEN"}""")
        transport.ok("GET", "/open/v1/streams/key", """{"streamKey":"sk"}""")
        transport.ok("POST", "/auth/v1/token", """{"accessToken":"access2","refreshToken":"refresh2","expiresIn":86400}""")
        val store = MemoryTokenStore(valid)
        assertEquals("sk", api(store = store).streamKey())
        val refresh = body(transport.calls("POST", "/auth/v1/token").single())
        assertEquals("refresh_token", refresh.getString("grantType"))
        assertEquals("refresh1", refresh.getString("refreshToken"))
        assertEquals("access2", store.tokens?.accessToken)
        assertEquals("Bearer access2", transport.calls("GET", "/open/v1/streams/key").last().headers["Authorization"])
    }

    @Test
    fun refreshesBeforeExpiry() {
        transport.ok("POST", "/auth/v1/token", """{"accessToken":"access2","refreshToken":"refresh2","expiresIn":"86400"}""")
        transport.ok("GET", "/open/v1/streams/key", """{"streamKey":"sk"}""")
        api(ChzzkTokens("access1", "refresh1", clock + 60_000)).streamKey()
        assertEquals(1, transport.calls("POST", "/auth/v1/token").size)
        assertEquals("Bearer access2", transport.calls("GET", "/open/v1/streams/key").single().headers["Authorization"])
    }

    @Test
    fun expiredRefreshTokenLogsOut() {
        transport.on("GET", "/open/v1/streams/key", 401, """{"code":401,"message":"INVALID_TOKEN"}""")
        transport.on("POST", "/auth/v1/token", 401, """{"code":401,"message":"INVALID_TOKEN"}""")
        val store = MemoryTokenStore(valid)
        try {
            api(store = store).streamKey()
            fail()
        } catch (e: ChzzkApiException) {
            assertEquals(401, e.status)
            assertEquals(ChzzkApi.MSG_LOGIN_EXPIRED, e.message)
        }
        assertNull(store.tokens)
    }

    @Test
    fun notLoggedInFailsWithoutNetwork() {
        try {
            api(tokens = null).streamKey()
            fail()
        } catch (e: ChzzkApiException) {
            assertEquals(ChzzkApi.MSG_NOT_LOGGED_IN, e.message)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun errorInBodyIsAnError() {
        transport.on("POST", "/open/v1/chats/send", 200, """{"code":403,"message":"FORBIDDEN"}""")
        try {
            api().sendChat("hi")
            fail()
        } catch (e: ChzzkApiException) {
            assertEquals(403, e.status)
            assertTrue(e.message!!.startsWith("권한이 없음"))
        }
    }

    @Test
    fun liveSettingParsesCategoryAndTags() {
        transport.ok(
            "GET", "/open/v1/lives/setting",
            """{"defaultLiveTitle":"산책","category":{"categoryType":"ETC","categoryId":"talk","categoryValue":"talk","posterImageUrl":""},"tags":["야외","서울"]}""",
        )
        val setting = api().liveSetting()
        assertEquals("산책", setting.title)
        assertEquals(ChzzkCategory("ETC", "talk", "talk"), setting.category)
        assertEquals(listOf("야외", "서울"), setting.tags)
    }

    @Test
    fun updateLiveSettingSendsOnlyGivenFields() {
        transport.ok("PATCH", "/open/v1/lives/setting")
        api().updateLiveSetting(title = "새 제목")
        val first = body(transport.requests.last())
        assertEquals("새 제목", first.getString("defaultLiveTitle"))
        assertFalse(first.has("categoryId"))
        assertFalse(first.has("tags"))

        api().updateLiveSetting(category = ChzzkApi.NO_CATEGORY, tags = emptyList())
        val second = body(transport.requests.last())
        assertEquals("", second.getString("categoryId"))
        assertFalse(second.has("categoryType"))
        assertEquals(0, second.getJSONArray("tags").length())

        api().updateLiveSetting(category = ChzzkCategory("GAME", "g1", "게임"), tags = listOf("a"))
        val third = body(transport.requests.last())
        assertEquals("GAME", third.getString("categoryType"))
        assertEquals("g1", third.getString("categoryId"))
    }

    @Test
    fun categorySearchUsesClientAuth() {
        transport.ok(
            "GET", "/open/v1/categories/search",
            """{"data":[{"categoryType":"GAME","categoryId":"lol","categoryValue":"리그 오브 레전드","posterImageUrl":"x"}]}""",
        )
        val found = api(tokens = null).searchCategories("리그", 10)
        assertEquals(listOf(ChzzkCategory("GAME", "lol", "리그 오브 레전드")), found)
        val request = transport.requests.single()
        assertEquals("id", request.headers["Client-Id"])
        assertEquals("secret", request.headers["Client-Secret"])
        assertNull(request.headers["Authorization"])
        assertTrue(request.url.endsWith("?size=10&query=%EB%A6%AC%EA%B7%B8"))
    }

    @Test
    fun subscribeSendsSessionKeyInQueryAndBody() {
        transport.ok("POST", "/open/v1/sessions/events/subscribe/chat")
        api().subscribe("chat", "key 1")
        val request = transport.requests.single()
        assertTrue(request.url.endsWith("/subscribe/chat?sessionKey=key+1"))
        assertEquals("key 1", body(request).getString("sessionKey"))
    }

    @Test
    fun chatSettingsKeepOtherFields() {
        transport.ok(
            "GET", "/open/v1/chats/settings",
            """{"chatAvailableCondition":"NONE","chatAvailableGroup":"ALL","minFollowerMinute":0,"allowSubscriberInFollowerMode":true,"chatSlowModeSec":0,"chatEmojiMode":false}""",
        )
        transport.ok("PUT", "/open/v1/chats/settings")
        val api = api()
        val current = api.chatSettings()
        assertFalse(current.followerOnly)
        api.updateChatSettings(current, slowModeSec = 5, followerOnly = true)
        val sent = body(transport.requests.last())
        assertEquals(5, sent.getInt("chatSlowModeSec"))
        assertEquals("FOLLOWER", sent.getString("chatAvailableGroup"))
        assertEquals("NONE", sent.getString("chatAvailableCondition"))
        assertTrue(sent.getBoolean("allowSubscriberInFollowerMode"))
    }

    @Test
    fun chatIsCutTo100Characters() {
        transport.ok("POST", "/open/v1/chats/send")
        api().sendChat("가".repeat(150))
        assertEquals(100, body(transport.requests.single()).getString("message").length)
    }

    @Test
    fun revokeClearsTokensEvenIfServerFails() {
        val store = MemoryTokenStore(valid)
        api(store = store).revoke() // 응답을 정해 두지 않아 실패한다
        assertNull(store.tokens)
        assertEquals("refresh_token", body(transport.requests.single()).getString("tokenTypeHint"))
    }
}
