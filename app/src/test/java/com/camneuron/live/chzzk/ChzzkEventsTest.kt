package com.camneuron.live.chzzk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChzzkEventsTest {
    @Test
    fun system() {
        assertEquals(
            ChzzkEvent.System("connected", "k1", null),
            ChzzkEvent.parse("SYSTEM", """{"type":"connected","data":{"sessionKey":"k1"}}"""),
        )
        assertEquals(
            ChzzkEvent.System("subscribed", null, "CHAT"),
            ChzzkEvent.parse("SYSTEM", """{"type":"subscribed","data":{"eventType":"CHAT","channelId":"c"}}"""),
        )
    }

    @Test
    fun chat() {
        val data = """{"channelId":"c","senderChannelId":"s","chatChannelId":"cc","profile":{"nickname":"산책중","badges":[],"verifiedMark":false},
            "userRoleCode":"common_user","content":"안녕하세요","emojis":{},"messageTime":1700000000000}"""
        assertEquals(ChzzkEvent.Chat("산책중", "안녕하세요", "common_user", 1_700_000_000_000), ChzzkEvent.parse("CHAT", data))
    }

    @Test
    fun donationAmountAsNumberOrString() {
        val expected = ChzzkEvent.Donation("치즈러버", 1000, "화이팅", "CHAT")
        assertEquals(expected, ChzzkEvent.parse("DONATION", """{"donationType":"CHAT","donatorNickname":"치즈러버","payAmount":"1000","donationText":"화이팅"}"""))
        assertEquals(expected, ChzzkEvent.parse("DONATION", """{"donationType":"CHAT","donatorNickname":"치즈러버","payAmount":1000,"donationText":"화이팅"}"""))
    }

    @Test
    fun subscription() {
        assertEquals(
            ChzzkEvent.Subscription("밤하늘", "티어 1", 3),
            ChzzkEvent.parse("SUBSCRIPTION", """{"subscriberNickname":"밤하늘","tierNo":1,"tierName":"티어 1","month":3}"""),
        )
    }

    @Test
    fun unknownOrBroken() {
        assertNull(ChzzkEvent.parse("OTHER", "{}"))
        assertNull(ChzzkEvent.parse("CHAT", "not json"))
        assertNull(ChzzkEvent.parse("CHAT", null))
        assertEquals("익명", (ChzzkEvent.parse("CHAT", """{"content":"x"}""") as ChzzkEvent.Chat).nickname)
    }
}
