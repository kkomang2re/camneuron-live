package com.camneuron.live.chzzk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChzzkLiveStatusTest {
    private val channel = "0123456789abcdef0123456789abcdef"
    private val path = ChzzkLiveStatus.path(channel)

    @Test
    fun parsesOpenBroadcastWithViewers() {
        val body = """{"code":200,"message":null,"content":{"liveTitle":"산책","status":"OPEN","concurrentUserCount":12,"accumulateCount":40}}"""
        assertEquals(ChzzkLiveStatus(open = true, viewers = 12), ChzzkLiveStatus.parse(body))
    }

    @Test
    fun parsesClosedBroadcast() {
        val body = """{"code":200,"message":null,"content":{"status":"CLOSE","concurrentUserCount":0}}"""
        assertEquals(ChzzkLiveStatus(open = false, viewers = 0), ChzzkLiveStatus.parse(body))
    }

    @Test
    fun unknownShapesGiveNull() {
        assertNull(ChzzkLiveStatus.parse("""{"code":404,"message":"채널이 존재하지 않습니다."}"""))
        assertNull(ChzzkLiveStatus.parse("""{"code":200,"content":null}"""))
        assertNull(ChzzkLiveStatus.parse("""{"code":200,"content":{"concurrentUserCount":3}}"""))
        assertNull(ChzzkLiveStatus.parse("<html>점검 중</html>"))
        assertNull(ChzzkLiveStatus.parse(""))
    }

    @Test
    fun fetchReadsTheChannelWithoutAnyToken() {
        val transport = FakeTransport()
        transport.ok("GET", path, """{"status":"OPEN","concurrentUserCount":3}""")
        assertEquals(ChzzkLiveStatus(true, 3), ChzzkLiveStatus.fetch(transport, channel, FakeTransport.BASE))
        val request = transport.calls("GET", path).single()
        assertTrue(request.headers.keys.none { it.equals("Authorization", ignoreCase = true) })
    }

    @Test
    fun fetchFailuresGiveNull() {
        val transport = FakeTransport()
        // 응답이 없다 (네트워크 오류)
        assertNull(ChzzkLiveStatus.fetch(transport, channel, FakeTransport.BASE))
        transport.on("GET", path, 500, "oops")
        assertNull(ChzzkLiveStatus.fetch(transport, channel, FakeTransport.BASE))
    }

    @Test
    fun strangeChannelIdsAreNotSent() {
        val transport = FakeTransport()
        assertNull(ChzzkLiveStatus.fetch(transport, "", FakeTransport.BASE))
        assertNull(ChzzkLiveStatus.fetch(transport, "abc/../../x?y=1", FakeTransport.BASE))
        assertTrue(transport.requests.isEmpty())
    }
}
