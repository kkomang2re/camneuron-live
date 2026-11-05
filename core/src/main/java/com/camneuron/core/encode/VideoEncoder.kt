package com.camneuron.core.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 카메라가 그리는 Surface를 하드웨어로 압축한다. 출력은 Annex-B H.264/HEVC.
 * 카메라와 인코더 사이에 GPU 단계([VideoEffects])를 두어 뷰티 보정을 하고, [watermark] 글자를 주면
 * 영상 오른쪽 아래에 얹어 보낸다 (테스터 체험판).
 */
class VideoEncoder(
    encoderName: String,
    mime: String,
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    constantBitrate: Boolean,
    watermark: String? = null,
    /** 키프레임 간격 (초). */
    keyFrameIntervalS: Int = DEFAULT_KEY_FRAME_INTERVAL_S,
) {
    private val codec = MediaCodec.createByCodecName(encoderName)

    /** 실제로 적용된 방식. CBR을 요청해도 인코더가 지원하지 않으면 VBR. */
    val constantBitrate: Boolean = constantBitrate && supportsCbr(codec.codecInfo, mime)

    private val codecSurface: Surface = try {
        try {
            configure(fullFormat(mime, width, height, fps, bitrate, this.constantBitrate, keyFrameIntervalS))
        } catch (e: Exception) {
            // 일부 기기는 선택 항목(우선순위, 색 정보 등)이 있으면 configure를 거부한다.
            Log.w(TAG, "configure failed, retrying with basic format", e)
            codec.reset()
            configure(basicFormat(mime, width, height, fps, bitrate, this.constantBitrate, keyFrameIntervalS))
        }
        codec.createInputSurface().also { codec.start() }
    } catch (e: Exception) {
        codec.release()
        throw e
    }

    /** GPU로 그릴 수 없는 기기면 카메라가 인코더에 바로 그린다: 뷰티 보정·워터마크 없이 보낸다 (송출이 멈추는 것보다 낫다). */
    private val effects: VideoEffects? =
        runCatching { VideoEffects(codecSurface, width, height, watermark) }
            .onFailure { Log.w(TAG, "video effects unavailable, camera draws to the encoder directly", it) }
            .getOrNull()

    /** 카메라가 그릴 곳: GPU 단계가 있으면 그 입력, 없으면 인코더 입력. */
    val inputSurface: Surface = effects?.inputSurface ?: codecSurface

    init {
        val mode = if (this.constantBitrate) "CBR" else "VBR"
        Log.i(TAG, "encoder $encoderName $mime ${width}x$height@$fps ${bitrate / 1000} kbps $mode")
    }

    /**
     * [keepRunning]이 false가 될 때까지 출력 버퍼를 꺼내 [sink]에 넘긴다.
     * 영상 프레임마다 [onFrame](pts, 바이트 수)을 부른다 (통계용).
     */
    fun drain(sink: Sink, keepRunning: () -> Boolean, onFrame: (Long, Int) -> Unit) {
        val info = MediaCodec.BufferInfo()
        while (keepRunning()) {
            val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (index < 0) continue // TRY_AGAIN_LATER, OUTPUT_FORMAT_CHANGED
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset).limit(info.offset + info.size)
                    val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val keyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val pts = if (config) 0L else info.presentationTimeUs
                    sink.onVideo(config, keyFrame, pts, buffer)
                    if (!config) onFrame(pts, info.size)
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
        }
    }

    /** 압축된 영상을 받는 곳 (캠뉴런은 OBS로, 야외 방송은 RTMP로). */
    fun interface Sink {
        /**
         * [config]면 SPS/PPS(HEVC는 VPS도)이고 [ptsUs]는 0이다. [data]는 Annex-B이며 이 호출 안에서만 유효하다.
         */
        fun onVideo(config: Boolean, keyFrame: Boolean, ptsUs: Long, data: ByteBuffer)
    }

    /** OBS가 영상을 뒤집어 쓰면 워터마크를 미리 반대로 그려 방송 화면에서 바로 읽히게 한다. 워터마크가 없으면 무시. */
    fun setWatermarkFlip(flipH: Boolean, flipV: Boolean) {
        effects?.setFlip(flipH, flipV)
    }

    /** 뷰티 보정 (각각 0~100). 연결을 끊지 않고 다음 프레임부터 바뀐다. */
    fun setBeauty(beauty: BeautySettings) {
        effects?.setBeauty(beauty.smooth / 100f, beauty.bright / 100f)
    }

    /**
     * 카메라 대신 [bitmap]을 화면 가득 보낸다 (null이면 다시 카메라). [bitmap]은 영상 크기로, 이 함수가 맡는다.
     * GPU 단계가 없는 기기면 할 수 없어 false를 돌려준다 (그때 [bitmap]은 바로 recycle한다).
     */
    fun setCover(bitmap: android.graphics.Bitmap?): Boolean {
        val stage = effects
        if (stage == null) {
            bitmap?.recycle()
            return false
        }
        stage.setCover(bitmap)
        return true
    }

    /**
     * 영상 위에 그림 층을 얹는다 ([VideoEffects.setLayer]). [bitmap]은 이 함수가 맡는다.
     * GPU 단계가 없는 기기면 false (그때 [bitmap]은 바로 recycle한다).
     */
    fun setLayer(index: Int, bitmap: android.graphics.Bitmap?, left: Float, top: Float, right: Float, bottom: Float, alpha: Float): Boolean {
        val stage = effects
        if (stage == null) {
            bitmap?.recycle()
            return false
        }
        stage.setLayer(index, bitmap, left, top, right, bottom, alpha)
        return true
    }

    fun requestKeyFrame() {
        runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    /** 연결을 끊지 않고 비트레이트를 바꾼다. */
    fun setBitrate(bitrate: Int) {
        runCatching {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
        }.onFailure { Log.w(TAG, "failed to change bitrate", it) }
    }

    fun release() {
        effects?.release() // 인코더 입력에 그리는 것을 먼저 멈춘다
        runCatching { codec.stop() }
        codec.release()
        codecSurface.release()
    }

    private fun configure(format: MediaFormat) {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    companion object {
        const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

        private const val TAG = "VideoEncoder"
        private const val DEQUEUE_TIMEOUT_US = 100_000L
        const val DEFAULT_KEY_FRAME_INTERVAL_S = 2

        // 화면이 멈춰 있어도 이 간격으로 프레임을 다시 보내 PC가 연결 끊김으로 오해하지 않게 한다.
        private const val REPEAT_FRAME_US = 100_000L

        /** 요청한 코덱의 인코더가 없으면 H.264로 대체한다. */
        fun pickMime(requestedCodec: Int): String =
            if (requestedCodec == Codecs.H265 && hasEncoder(MIME_HEVC)) MIME_HEVC else MIME_AVC

        /** 해당 크기를 지원하는 인코더 이름. 하드웨어 인코더를 우선한다. */
        fun findEncoder(mime: String, width: Int, height: Int): String? {
            val candidates = encodersFor(mime).filter { info ->
                runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) == true
                }.getOrDefault(false)
            }
            return (candidates.firstOrNull { isHardware(it) } ?: candidates.firstOrNull())?.name
        }

        private fun hasEncoder(mime: String) = encodersFor(mime).any { isHardware(it) }

        private fun encodersFor(mime: String): List<MediaCodecInfo> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }

        private fun isHardware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            }

        private fun supportsCbr(info: MediaCodecInfo, mime: String): Boolean = runCatching {
            info.getCapabilitiesForType(mime).encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
        }.getOrDefault(false)

        private fun basicFormat(mime: String, width: Int, height: Int, fps: Int, bitrate: Int, cbr: Boolean, keyFrameS: Int) =
            MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameS)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_US)
                // 정지 화면에서도 화질을 유지하려면 CBR이 낫다. VBR은 장면이 단순하면 비트레이트를 크게 줄인다.
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    if (cbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
            }

        private fun fullFormat(mime: String, width: Int, height: Int, fps: Int, bitrate: Int, cbr: Boolean, keyFrameS: Int) =
            basicFormat(mime, width, height, fps, bitrate, cbr, keyFrameS).apply {
                setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = 실시간
                setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                setInteger(MediaFormat.KEY_LATENCY, 1) // 입력 1프레임마다 바로 출력
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // B-프레임은 지연을 늘린다
                }
            }
    }
}
