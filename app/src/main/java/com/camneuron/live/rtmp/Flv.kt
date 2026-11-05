package com.camneuron.live.rtmp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * RTMP 영상·소리 메시지의 본문 (FLV 태그 본문과 같다). 영상은 H.264(AVC), 소리는 AAC만 쓴다.
 * 인코더가 주는 Annex-B(시작 코드로 나뉜 NAL)를 FLV가 쓰는 길이 앞붙임 형식으로 바꾼다.
 */
object Flv {
    private const val CODEC_AVC = 7
    private const val FRAME_KEY = 1
    private const val FRAME_INTER = 2
    private const val AVC_SEQUENCE_HEADER = 0
    private const val AVC_NALU = 1

    /** AAC, 44kHz 표시, 16비트, 스테레오 표시. AAC는 실제 값과 관계없이 늘 이 바이트를 쓴다 (FLV 규격). */
    private const val AAC_HEADER = 0xAF
    private const val AAC_SEQUENCE_HEADER = 0
    private const val AAC_RAW = 1

    const val NAL_SLICE_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    /** Annex-B 바이트에서 NAL들을 꺼낸다 (시작 코드 3바이트·4바이트 모두). 시작 코드가 없으면 전체를 NAL 하나로 본다. */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>() // 각 NAL이 시작하는 위치 (시작 코드 바로 뒤)
        val codeStarts = mutableListOf<Int>() // 각 시작 코드의 위치
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                val codeStart = if (i > 0 && data[i - 1].toInt() == 0) i - 1 else i
                codeStarts += codeStart
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return if (data.isEmpty()) emptyList() else listOf(data)
        return starts.indices.mapNotNull { n ->
            val end = if (n + 1 < starts.size) codeStarts[n + 1] else data.size
            // NAL 끝의 0 바이트(trailing zero)는 다음 시작 코드의 일부로 본다.
            var trimmed = end
            while (trimmed > starts[n] && data[trimmed - 1].toInt() == 0 && n + 1 < starts.size) trimmed--
            if (trimmed > starts[n]) data.copyOfRange(starts[n], trimmed) else null
        }
    }

    fun nalType(nal: ByteArray): Int = nal[0].toInt() and 0x1F

    /** SPS·PPS로 AVCDecoderConfigurationRecord를 만들어 영상 시퀀스 헤더 본문으로 감싼다. */
    fun avcSequenceHeader(sps: ByteArray, pps: ByteArray): ByteArray {
        require(sps.size >= 4) { "SPS too short" }
        val out = ByteArrayOutputStream(16 + sps.size + pps.size)
        out.write((FRAME_KEY shl 4) or CODEC_AVC)
        out.write(AVC_SEQUENCE_HEADER)
        out.write(0); out.write(0); out.write(0) // composition time
        out.write(1) // configurationVersion
        out.write(sps[1].toInt()) // profile
        out.write(sps[2].toInt()) // profile compatibility
        out.write(sps[3].toInt()) // level
        out.write(0xFF) // NAL 길이 4바이트 (lengthSizeMinusOne = 3)
        out.write(0xE1) // SPS 1개
        out.write(sps.size shr 8); out.write(sps.size and 0xFF); out.write(sps)
        out.write(1) // PPS 1개
        out.write(pps.size shr 8); out.write(pps.size and 0xFF); out.write(pps)
        return out.toByteArray()
    }

    /**
     * 압축된 프레임 하나의 영상 메시지 본문. SPS·PPS·AUD는 시퀀스 헤더로 따로 보내므로 뺀다.
     * B-프레임을 쓰지 않으므로(VideoEncoder) composition time은 0이다. 보낼 NAL이 없으면 null.
     */
    fun avcFrame(annexB: ByteArray, keyFrame: Boolean): ByteArray? {
        val nals = splitAnnexB(annexB).filter { nalType(it) !in SKIPPED_IN_FRAMES }
        if (nals.isEmpty()) return null
        val out = ByteArrayOutputStream(5 + nals.sumOf { it.size + 4 })
        out.write(((if (keyFrame) FRAME_KEY else FRAME_INTER) shl 4) or CODEC_AVC)
        out.write(AVC_NALU)
        out.write(0); out.write(0); out.write(0)
        for (nal in nals) {
            out.write(RtmpMessage.int32(nal.size.toLong()))
            out.write(nal)
        }
        return out.toByteArray()
    }

    private val SKIPPED_IN_FRAMES = setOf(NAL_SPS, NAL_PPS, NAL_AUD)

    fun aacSequenceHeader(audioSpecificConfig: ByteArray): ByteArray =
        byteArrayOf(AAC_HEADER.toByte(), AAC_SEQUENCE_HEADER.toByte()) + audioSpecificConfig

    fun aacFrame(data: ByteBuffer): ByteArray {
        val out = ByteArray(2 + data.remaining())
        out[0] = AAC_HEADER.toByte()
        out[1] = AAC_RAW.toByte()
        data.duplicate().get(out, 2, data.remaining())
        return out
    }

    /** 방송 시작 때 한 번 보내는 영상 정보 (@setDataFrame onMetaData). 서버가 화질 표시와 검사에 쓴다. */
    fun metadata(
        width: Int,
        height: Int,
        fps: Int,
        videoKbps: Int,
        audio: Boolean,
        audioSampleRate: Int,
        audioChannels: Int,
        audioKbps: Int,
        encoder: String,
    ): ByteArray {
        val entries = linkedMapOf<String, Any?>(
            "duration" to 0,
            "width" to width,
            "height" to height,
            "videodatarate" to videoKbps,
            "framerate" to fps,
            "videocodecid" to CODEC_AVC,
        )
        if (audio) {
            entries["audiodatarate"] = audioKbps
            entries["audiosamplerate"] = audioSampleRate
            entries["audiosamplesize"] = 16
            entries["stereo"] = audioChannels == 2
            entries["audiocodecid"] = 10
        }
        entries["encoder"] = encoder
        entries["filesize"] = 0
        return Amf0.encode("@setDataFrame", "onMetaData", Amf0.EcmaArray(entries))
    }
}
