package com.camneuron.live.rtmp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * RTMP 명령(connect, publish 등)에 쓰는 AMF0 직렬화. 필요한 형식만 다룬다.
 * 값은 Double/Int(숫자), Boolean, String, null, Map(객체), List(배열)로 나타낸다.
 */
object Amf0 {
    private const val NUMBER = 0x00
    private const val BOOLEAN = 0x01
    private const val STRING = 0x02
    private const val OBJECT = 0x03
    private const val NULL = 0x05
    private const val UNDEFINED = 0x06
    private const val ECMA_ARRAY = 0x08
    private const val OBJECT_END = 0x09
    private const val STRICT_ARRAY = 0x0A
    private const val DATE = 0x0B
    private const val LONG_STRING = 0x0C

    /** onMetaData처럼 ECMA 배열로 보내야 하는 값. */
    class EcmaArray(val entries: Map<String, Any?>)

    fun encode(vararg values: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        values.forEach { write(out, it) }
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(NULL)
            is Number -> {
                out.write(NUMBER)
                writeLong(out, java.lang.Double.doubleToLongBits(value.toDouble()))
            }
            is Boolean -> {
                out.write(BOOLEAN)
                out.write(if (value) 1 else 0)
            }
            is String -> {
                val bytes = value.toByteArray(Charsets.UTF_8)
                if (bytes.size > 0xFFFF) {
                    out.write(LONG_STRING)
                    writeInt(out, bytes.size)
                } else {
                    out.write(STRING)
                    writeShort(out, bytes.size)
                }
                out.write(bytes)
            }
            is EcmaArray -> {
                out.write(ECMA_ARRAY)
                writeInt(out, value.entries.size)
                writeProperties(out, value.entries)
            }
            is Map<*, *> -> {
                out.write(OBJECT)
                @Suppress("UNCHECKED_CAST")
                writeProperties(out, value as Map<String, Any?>)
            }
            is List<*> -> {
                out.write(STRICT_ARRAY)
                writeInt(out, value.size)
                value.forEach { write(out, it) }
            }
            else -> throw IllegalArgumentException("AMF0 cannot encode ${value.javaClass}")
        }
    }

    private fun writeProperties(out: ByteArrayOutputStream, map: Map<String, Any?>) {
        for ((key, v) in map) {
            val name = key.toByteArray(Charsets.UTF_8)
            writeShort(out, name.size)
            out.write(name)
            write(out, v)
        }
        writeShort(out, 0)
        out.write(OBJECT_END)
    }

    /** 메시지 하나에 든 값을 모두 읽는다. 모르는 형식을 만나면 거기까지만 돌려준다. */
    fun decodeAll(data: ByteArray): List<Any?> {
        val buffer = ByteBuffer.wrap(data)
        val values = mutableListOf<Any?>()
        while (buffer.hasRemaining()) {
            values += runCatching { read(buffer) }.getOrElse { return values }
        }
        return values
    }

    private fun read(buffer: ByteBuffer): Any? = when (val type = buffer.get().toInt() and 0xFF) {
        NUMBER -> buffer.double
        BOOLEAN -> buffer.get().toInt() != 0
        STRING -> readString(buffer, buffer.short.toInt() and 0xFFFF)
        LONG_STRING -> readString(buffer, buffer.int)
        OBJECT -> readProperties(buffer)
        ECMA_ARRAY -> {
            buffer.int // 개수는 믿지 않고 끝 표시까지 읽는다
            readProperties(buffer)
        }
        STRICT_ARRAY -> List(buffer.int) { read(buffer) }
        DATE -> buffer.double.also { buffer.short }
        NULL, UNDEFINED -> null
        else -> throw IllegalArgumentException("unsupported AMF0 type $type")
    }

    private fun readProperties(buffer: ByteBuffer): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            val key = readString(buffer, buffer.short.toInt() and 0xFFFF)
            if (key.isEmpty() && (buffer.get(buffer.position()).toInt() and 0xFF) == OBJECT_END) {
                buffer.get()
                return map
            }
            map[key] = read(buffer)
        }
    }

    private fun readString(buffer: ByteBuffer, length: Int): String {
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) {
        out.write(v shr 8 and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        writeShort(out, v ushr 16)
        writeShort(out, v and 0xFFFF)
    }

    private fun writeLong(out: ByteArrayOutputStream, v: Long) {
        writeInt(out, (v ushr 32).toInt())
        writeInt(out, v.toInt())
    }
}
