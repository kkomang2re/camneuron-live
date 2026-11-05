package com.camneuron.live.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtmpUrlTest {
    @Test
    fun parse_hostPortApp() {
        val url = RtmpUrl.parse("rtmp://ingest.example.com:8080/relay")!!
        assertEquals("ingest.example.com", url.host)
        assertEquals(8080, url.port)
        assertEquals("relay", url.app)
        assertEquals("rtmp://ingest.example.com:8080/relay", url.tcUrl)
    }

    @Test
    fun parse_defaultPortNestedAppTrailingSlash() {
        val url = RtmpUrl.parse("  RTMP://live.example.com/app/inst/ ")!!
        assertEquals(1935, url.port)
        assertEquals("app/inst", url.app)
        assertEquals("rtmp://live.example.com/app/inst", url.tcUrl)
    }

    @Test
    fun parse_ipv6() {
        val url = RtmpUrl.parse("rtmp://[::1]:1936/live")!!
        assertEquals("::1", url.host)
        assertEquals(1936, url.port)
        assertEquals("rtmp://[::1]:1936/live", url.tcUrl)
    }

    @Test
    fun parse_rejectsBadInput() {
        assertNull(RtmpUrl.parse("http://example.com/live"))
        assertNull(RtmpUrl.parse("rtmp://example.com"))
        assertNull(RtmpUrl.parse("rtmp://example.com/"))
        assertNull(RtmpUrl.parse("rtmp://example.com:99999/live"))
        assertNull(RtmpUrl.parse("rtmp://:1935/live"))
        assertNull(RtmpUrl.parse("rtmps://example.com/live"))
    }
}
