package com.camneuron.core.encode

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 폰 마이크 소리를 녹음해 Opus로 압축한다. Opus 인코더가 없는 폰(Android 10 미만)은 PCM을 그대로 넘긴다.
 * [aac]면 AAC-LC로 압축한다 (야외 방송의 RTMP용). AAC 인코더는 모든 Android 폰에 있으므로 PCM으로 바꾸지 않는다.
 * 자기 스레드에서 20ms씩 녹음하며 결과를 [Sink]로 넘긴다.
 * 시각은 첫 샘플을 녹음한 순간의 System.nanoTime 기준 µs다 (세션이 영상 시계로 바꾼다).
 */
class AudioEncoder(private val sink: Sink, private val aac: Boolean = false) {
    interface Sink {
        /**
         * 소리 패킷보다 먼저 한 번 온다. [codec]은 [Codecs.OPUS] 또는 [Codecs.PCM], [aac]로 만들었으면 [Codecs.AAC].
         * [codecHeader]는 Opus면 OpusHead, AAC면 AudioSpecificConfig, PCM이면 비어 있다.
         */
        fun onAudioConfig(codec: Int, sampleRate: Int, channels: Int, codecHeader: ByteArray)

        /** [data]는 이 호출 안에서만 유효하다. */
        fun onAudioPacket(ptsUs: Long, data: ByteBuffer)

        /** 방금 녹음한 20ms의 최대 음량 (0~1). */
        fun onAudioLevel(peak: Float)

        /** 녹음을 시작하지 못했거나 도중에 멈춤. 스레드는 스스로 끝난다. */
        fun onAudioError(encoder: AudioEncoder, message: String)
    }

    private val thread = Thread(::run, "audio")

    @Volatile
    private var running = true
    private var configSent = false

    /** true면 녹음은 계속하되 무음을 압축해 보낸다 (시각과 패킷 흐름은 그대로). 기본은 꺼짐. */
    @Volatile
    var muted = false

    fun start() = thread.start()

    fun stop() {
        running = false
        thread.join(1000) // read()는 20ms마다 돌아오므로 곧 끝난다
    }

    private fun run() {
        val record = openRecord()
        if (record == null) {
            sink.onAudioError(this, "마이크를 열 수 없음")
            return
        }
        val channels = record.channelCount
        val encoder = if (aac) openAac(channels) else openOpus(channels)
        if (aac && encoder == null) {
            record.release()
            sink.onAudioError(this, "AAC 인코더를 열 수 없음")
            return
        }
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "다른 앱이 마이크를 쓰는 중" }
            val name = if (aac) "AAC" else if (encoder != null) "Opus" else "PCM"
            Log.i(TAG, "recording $SAMPLE_RATE Hz, $channels ch, $name")
            if (encoder == null) sendConfig(Codecs.PCM, channels, ByteArray(0))
            loop(record, encoder, channels)
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "audio stopped", e)
                sink.onAudioError(this, e.message ?: e.javaClass.simpleName)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            encoder?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
    }

    private fun loop(record: AudioRecord, encoder: MediaCodec?, channels: Int) {
        val frameBytes = channels * BYTES_PER_SAMPLE
        val chunk = ByteArray(CHUNK_FRAMES * frameBytes)
        val timestamp = AudioTimestamp()
        val info = MediaCodec.BufferInfo()
        var framesRead = 0L
        while (running) {
            val read = record.read(chunk, 0, chunk.size)
            check(read >= 0) { "AudioRecord.read: $read" }
            if (read == 0) continue
            val frames = read / frameBytes
            val ptsUs = captureTimeNs(record, timestamp, framesRead, frames) / 1000
            framesRead += frames
            if (muted) java.util.Arrays.fill(chunk, 0, read, 0.toByte())
            sink.onAudioLevel(peak(chunk, read))
            if (encoder == null) {
                sink.onAudioPacket(ptsUs, ByteBuffer.wrap(chunk, 0, read))
            } else {
                encode(encoder, info, chunk, read, ptsUs, channels)
                drain(encoder, info, channels)
            }
        }
    }

    /**
     * 이번에 읽은 첫 샘플을 녹음한 시각. 오디오 장치가 알려 주는 (샘플 위치, 시각) 쌍으로 계산하고,
     * 그 값이 없거나 이상하면 방금 읽은 시각에서 거꾸로 센다 (장치 안의 지연만큼 늦게 잡힌다).
     */
    private fun captureTimeNs(record: AudioRecord, ts: AudioTimestamp, firstFrame: Long, frames: Int): Long {
        val fallback = System.nanoTime() - framesToNs(frames.toLong())
        if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return fallback
        val fromDevice = ts.nanoTime + framesToNs(firstFrame - ts.framePosition)
        return if (abs(fromDevice - fallback) < MAX_TIMESTAMP_ERROR_NS) fromDevice else fallback
    }

    private fun encode(codec: MediaCodec, info: MediaCodec.BufferInfo, pcm: ByteArray, size: Int, ptsUs: Long, channels: Int) {
        val frameBytes = channels * BYTES_PER_SAMPLE
        var offset = 0
        while (offset < size && running) {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) {
                drain(codec, info, channels) // 출력이 밀려 입력 버퍼가 없으면 먼저 비운다
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: continue
            buffer.clear()
            val length = minOf(buffer.remaining(), size - offset) / frameBytes * frameBytes
            buffer.put(pcm, offset, length)
            codec.queueInputBuffer(index, 0, length, ptsUs + framesToNs((offset / frameBytes).toLong()) / 1000, 0)
            offset += length
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, channels: Int) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                codec.outputFormat.getByteBuffer("csd-0")?.let { onCodecConfig(it, channels) }
                continue
            }
            if (index < 0) return
            try {
                val buffer = codec.getOutputBuffer(index) ?: continue
                if (info.size <= 0) continue
                buffer.position(info.offset).limit(info.offset + info.size)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    onCodecConfig(buffer, channels)
                } else {
                    if (!configSent) {
                        if (aac) sendConfig(Codecs.AAC, channels, AacConfig.audioSpecificConfig(SAMPLE_RATE, channels))
                        else sendConfig(Codecs.OPUS, channels, OpusHead.standard(channels, SAMPLE_RATE))
                    }
                    sink.onAudioPacket(info.presentationTimeUs, buffer)
                }
            } finally {
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    /** 인코더가 준 설정 데이터 (출력 형식의 csd-0 또는 CODEC_CONFIG 버퍼). */
    private fun onCodecConfig(csd: ByteBuffer, channels: Int) {
        if (aac) {
            if (csd.remaining() >= 2) sendConfig(Codecs.AAC, channels, AacConfig.copy(csd))
        } else {
            OpusHead.find(csd)?.let { sendConfig(Codecs.OPUS, channels, it) }
        }
    }

    private fun sendConfig(codec: Int, channels: Int, header: ByteArray) {
        if (configSent) return
        configSent = true
        sink.onAudioConfig(codec, SAMPLE_RATE, channels, header)
    }

    /** 영상을 찍는 방향의 소리를 담도록 맞춘 캠코더 마이크. 스테레오가 안 되면 모노. */
    @SuppressLint("MissingPermission") // 세션이 마이크 권한을 확인한 뒤에만 만든다
    private fun openRecord(): AudioRecord? {
        for (mask in intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
            val channels = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) continue
            val bytes = maxOf(minBytes, CHUNK_FRAMES * channels * BYTES_PER_SAMPLE * 4)
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT, bytes)
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        return null
    }

    private fun openOpus(channels: Int): MediaCodec? {
        val name = opusEncoderName() ?: return null
        return runCatching { startCodec(MediaCodec.createByCodecName(name), opusFormat(channels)) }
            .onFailure { Log.w(TAG, "Opus encoder failed, sending PCM", it) }.getOrNull()
    }

    private fun openAac(channels: Int): MediaCodec? =
        runCatching { startCodec(MediaCodec.createEncoderByType(MIME_AAC), aacFormat(channels)) }
            .onFailure { Log.w(TAG, "AAC encoder failed", it) }.getOrNull()

    private fun startCodec(codec: MediaCodec, format: MediaFormat): MediaCodec = codec.apply {
        try {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000

        private const val TAG = "AudioEncoder"
        private const val MIME_OPUS = "audio/opus"
        private const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_FRAMES = SAMPLE_RATE / 50 // 20ms, Opus 프레임 하나
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val MAX_TIMESTAMP_ERROR_NS = 200_000_000L

        /** 마이크를 켜면 쓸 코덱 (handshake에 알린다). */
        fun preferredCodec(): Int = if (opusEncoderName() != null) Codecs.OPUS else Codecs.PCM

        private fun opusFormat(channels: Int): MediaFormat =
            MediaFormat.createAudioFormat(MIME_OPUS, SAMPLE_RATE, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 2) 128_000 else 64_000)
            }

        /** 방송 플랫폼들이 권하는 AAC 음질 (스테레오 160kbps). */
        private fun aacFormat(channels: Int): MediaFormat =
            MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 2) 160_000 else 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * channels * BYTES_PER_SAMPLE)
            }

        private fun opusEncoderName(): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(opusFormat(2)) }.getOrNull()
        }

        private fun framesToNs(frames: Long): Long = frames * 1_000_000_000L / SAMPLE_RATE

        /** 16비트 little-endian 샘플의 최댓값 (0~1). */
        private fun peak(pcm: ByteArray, size: Int): Float {
            var max = 0
            var i = 0
            while (i + 1 < size) {
                val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                max = maxOf(max, abs(sample))
                i += 2
            }
            return max / 32768f
        }
    }
}
