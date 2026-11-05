package com.camneuron.live.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Amf0Test {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun encode_primitives() {
        // 문자열 "a", 숫자 1.0, true, null
        assertArrayEquals(
            bytes(
                0x02, 0x00, 0x01, 'a'.code,
                0x00, 0x3F, 0xF0, 0, 0, 0, 0, 0, 0,
                0x01, 0x01,
                0x05,
            ),
            Amf0.encode("a", 1, true, null),
        )
    }

    @Test
    fun encode_objectEndsWithEmptyKeyAndMarker() {
        assertArrayEquals(
            bytes(0x03, 0x00, 0x01, 'k'.code, 0x02, 0x00, 0x01, 'v'.code, 0x00, 0x00, 0x09),
            Amf0.encode(mapOf("k" to "v")),
        )
    }

    @Test
    fun encode_ecmaArrayHasCount() {
        val encoded = Amf0.encode(Amf0.EcmaArray(mapOf("w" to 1920)))
        assertEquals(0x08, encoded[0].toInt())
        assertArrayEquals(bytes(0, 0, 0, 1), encoded.copyOfRange(1, 5))
        assertArrayEquals(bytes(0x00, 0x00, 0x09), encoded.copyOfRange(encoded.size - 3, encoded.size))
    }

    @Test
    fun decode_roundTripsServerReply() {
        val reply = Amf0.encode(
            "_result", 1, mapOf("fmsVer" to "FMS/3,0,1,123", "capabilities" to 31),
            mapOf("level" to "status", "code" to "NetConnection.Connect.Success"),
        )
        val values = Amf0.decodeAll(reply)
        assertEquals("_result", values[0])
        assertEquals(1.0, values[1])
        assertEquals(31.0, (values[2] as Map<*, *>)["capabilities"])
        assertEquals("NetConnection.Connect.Success", (values[3] as Map<*, *>)["code"])
    }

    @Test
    fun decode_ecmaArrayAndStrictArray() {
        val values = Amf0.decodeAll(Amf0.encode(Amf0.EcmaArray(mapOf("a" to false)), listOf("x", 2)))
        assertEquals(mapOf("a" to false), values[0])
        assertEquals(listOf("x", 2.0), values[1])
    }

    @Test
    fun decode_stopsAtUnknownType() {
        val data = Amf0.encode("ok") + bytes(0x11, 0x00)
        assertEquals(listOf<Any?>("ok"), Amf0.decodeAll(data))
    }
}
