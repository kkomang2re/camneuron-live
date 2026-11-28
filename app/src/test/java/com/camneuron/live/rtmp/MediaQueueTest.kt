package com.camneuron.live.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaQueueTest {
    private fun item(kind: MediaQueue.Kind, size: Int, ts: Long = 0) =
        MediaQueue.Item(kind, ChunkWriter.CSID_VIDEO, RtmpMessage.VIDEO, ts, ByteArray(size))

    private fun audio(ts: Long, size: Int = 10) =
        MediaQueue.Item(MediaQueue.Kind.AUDIO, ChunkWriter.CSID_AUDIO, RtmpMessage.AUDIO, ts, ByteArray(size))

    private fun timed(clock: () -> Long, onNeedKeyFrame: () -> Unit = {}) =
        MediaQueue(1_000_000, maxDelayMs = 2_000, clockMs = clock, onNeedKeyFrame = onNeedKeyFrame)

    @Test
    fun overflow_dropsEverythingQueuedAndWaitsForKeyFrame() {
        var keyRequests = 0
        val queue = MediaQueue(100) { keyRequests++ }
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 40)))
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 40)))
        assertTrue(queue.offer(audio(0)))
        // 넘친다: 쌓인 영상 둘과 소리, 그리고 이 프레임을 버리고 키프레임을 요청한다.
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 40)))
        assertEquals(1, keyRequests)
        assertEquals(3, queue.droppedFrames)
        assertEquals(0, queue.queuedBytes())
        // 키프레임 전에는 자리가 있어도 프레임도 소리도 받지 않는다 (영상 없이 소리만 가면 시청자 지연이 된다).
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 5)))
        assertFalse(queue.offer(audio(0)))
        assertEquals(1, keyRequests)
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 50)))
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 5)))
        assertTrue(queue.offer(audio(0)))

        assertEquals(MediaQueue.Kind.KEY_FRAME, queue.take(0)!!.kind)
        assertEquals(MediaQueue.Kind.FRAME, queue.take(0)!!.kind)
        assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
        assertNull(queue.take(0))

        val stats = queue.takeStats()
        assertEquals(1, stats.drops)
        assertEquals(2, stats.droppedAudio)
        assertEquals(0.9f, stats.fill, 0.001f)
    }

    @Test
    fun essentialIsNeverDropped() {
        val queue = MediaQueue(10)
        assertTrue(queue.offer(item(MediaQueue.Kind.ESSENTIAL, 50)))
        assertTrue(queue.offer(item(MediaQueue.Kind.ESSENTIAL, 50)))
        assertEquals(100, queue.queuedBytes())
    }

    @Test
    fun oversizedKeyFrameIsStillQueued() {
        var keyRequests = 0
        val queue = MediaQueue(10) { keyRequests++ }
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 30)))
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 30))) // 앞의 것을 버리고 넣는다
        assertEquals(30, queue.queuedBytes())
        assertEquals(0, keyRequests) // 키프레임이 왔으니 달라고 할 것이 없다
    }

    @Test
    fun waitingTooLong_skipsEvenWithRoomLeft() {
        var now = 0L
        var keyRequests = 0
        val queue = timed({ now }) { keyRequests++ }
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        now = 1_000
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        now = 2_000
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100))) // 딱 2초: 아직 괜찮다
        assertEquals(0, keyRequests)

        // 맨 앞이 2초 넘게 기다렸다 (업로드가 느리다). 자리는 넉넉해도 쌓인 것을 버리고 키프레임부터 다시.
        now = 2_001
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(1, keyRequests)
        assertEquals(4, queue.droppedFrames)
        assertEquals(0, queue.queuedBytes())
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
    }

    @Test
    fun fillReportsTheFullerOfBytesAndDelay() {
        var now = 0L
        val queue = MediaQueue(1_000, maxDelayMs = 2_000, clockMs = { now })
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100))) // 바이트로는 10%
        now = 1_500
        val stats = queue.takeStats()
        assertEquals(0.75f, stats.fill, 0.001f) // 시간으로는 75%
        assertEquals(1_500L, stats.delayMs)

        // 1.5초 기다린 뒤에 나갔다. 그 다음부터는 밀림이 없다.
        queue.take(0)
        assertEquals(1_500L, queue.takeStats().delayMs)
        assertEquals(0L, queue.takeStats().delayMs)
    }

    @Test
    fun withoutDelayLimitOnlyBytesCount() {
        var now = 0L
        val queue = MediaQueue(1_000, clockMs = { now })
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        now = 60_000
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertTrue(queue.offer(audio(60_000)))
        assertEquals(0.21f, queue.takeStats().fill, 0.001f)
    }

    @Test
    fun soundIsSkippedTogetherWithVideo() {
        var now = 0L
        var keyRequests = 0
        val queue = timed({ now }) { keyRequests++ }
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertTrue(queue.offer(audio(0)))
        now = 2_500
        // 소리가 왔을 때 밀려 있었다: 영상도 소리도 (이 소리까지) 버린다. 소리만 남겨 보내지 않는다.
        assertFalse(queue.offer(audio(2_500)))
        assertEquals(1, keyRequests)
        assertEquals(0, queue.queuedBytes())
        assertEquals(2, queue.droppedFrames)
        assertEquals(2, queue.takeStats().droppedAudio)
        assertFalse(queue.offer(audio(2_520)))
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertTrue(queue.offer(audio(2_540)))
    }

    @Test
    fun skipContinuesTheTimelineWithoutAGap() {
        var now = 0L
        var keyRequests = 0
        val queue = timed({ now }) { keyRequests++ }
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100, ts = 0)))
        assertTrue(queue.offer(audio(0)))
        assertEquals(0L, queue.take(0)!!.sendTimestampMs)
        assertEquals(0L, queue.take(0)!!.sendTimestampMs)

        // 업로드가 멈췄다: 영상과 소리가 쌓인다.
        now = 100
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100, ts = 100)))
        assertTrue(queue.offer(audio(100)))
        now = 1_100
        assertTrue(queue.offer(audio(1_100)))
        assertEquals(0L, queue.takeStats().skippedMs)
        now = 2_101
        assertFalse(queue.offer(audio(2_101)))
        assertEquals(1, keyRequests)

        // 키프레임이 오면 방금 보낸 것(0ms) 바로 뒤에 잇는다. 건너뛴 시간은 방송에서 빠진다.
        now = 2_150
        val key = item(MediaQueue.Kind.KEY_FRAME, 100, ts = 2_150)
        assertTrue(queue.offer(key))
        assertEquals(20L, key.sendTimestampMs)
        assertEquals(2_150L, key.timestampMs)
        // 그 뒤로는 영상도 소리도 같은 만큼 당겨진다 (둘이 어긋나지 않는다).
        val sound = audio(2_160)
        val frame = item(MediaQueue.Kind.FRAME, 100, ts = 2_183)
        assertTrue(queue.offer(sound))
        assertTrue(queue.offer(frame))
        assertEquals(30L, sound.sendTimestampMs)
        assertEquals(53L, frame.sendTimestampMs)

        val stats = queue.takeStats()
        assertEquals(2_130L, stats.skippedMs)
        assertEquals(1, stats.drops)
        assertEquals(3, stats.droppedAudio)
    }

    @Test
    fun skipKeepsSequenceHeadersAndNeverSendsTimeBackwards() {
        var now = 0L
        val queue = timed({ now })
        assertTrue(queue.offer(audio(0)))
        assertEquals(0L, queue.take(0)!!.sendTimestampMs)
        now = 500
        assertTrue(queue.offer(audio(500)))
        now = 2_000
        val header = item(MediaQueue.Kind.ESSENTIAL, 40, ts = 2_000) // 카메라를 바꿔 새로 온 시퀀스 헤더
        assertTrue(queue.offer(header))
        assertEquals(2_000L, header.sendTimestampMs)

        now = 2_600
        assertFalse(queue.offer(audio(2_600)))
        // 헤더는 남고, 방금 보낸 것 바로 뒤로 당겨진다.
        assertEquals(40, queue.queuedBytes())
        assertEquals(20L, header.sendTimestampMs)

        now = 2_610
        val key = item(MediaQueue.Kind.KEY_FRAME, 100, ts = 2_610)
        val sound = audio(2_620)
        assertTrue(queue.offer(key))
        assertTrue(queue.offer(sound))
        assertEquals(40L, key.sendTimestampMs) // 헤더보다 이르지 않다
        assertEquals(50L, sound.sendTimestampMs)
        assertEquals(MediaQueue.Kind.ESSENTIAL, queue.take(0)!!.kind)
        assertEquals(MediaQueue.Kind.KEY_FRAME, queue.take(0)!!.kind)
        assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
    }

    @Test
    fun sequenceHeaderArrivingWhileWaitingStaysAtTheResumePoint() {
        var now = 0L
        val queue = timed({ now })
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100, ts = 0)))
        assertEquals(0L, queue.take(0)!!.sendTimestampMs)
        now = 10
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100, ts = 10)))
        now = 2_011
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100, ts = 2_011)))

        // 키프레임을 기다리는 동안 인코더가 새로 만들어져 (카메라 전환) 새 헤더와 키프레임이 온다.
        now = 3_000
        val header = item(MediaQueue.Kind.ESSENTIAL, 40, ts = 3_000)
        val key = item(MediaQueue.Kind.KEY_FRAME, 100, ts = 3_000)
        assertTrue(queue.offer(header))
        assertTrue(queue.offer(key))
        assertEquals(20L, header.sendTimestampMs)
        assertEquals(40L, key.sendTimestampMs)
        assertEquals(2_960L, queue.takeStats().skippedMs)
    }

    @Test
    fun beforeAnythingWasSent_thereIsNothingToContinueFrom() {
        var now = 0L
        val queue = timed({ now })
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100, ts = 1_000)))
        now = 2_001
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100, ts = 3_001)))
        val key = item(MediaQueue.Kind.KEY_FRAME, 100, ts = 3_050)
        assertTrue(queue.offer(key))
        assertEquals(3_050L, key.sendTimestampMs)
        assertEquals(0L, queue.takeStats().skippedMs)
    }

    @Test
    fun aStuckSequenceHeaderDoesNotMakeEveryFrameSkip() {
        var now = 0L
        var keyRequests = 0
        val queue = timed({ now }) { keyRequests++ }
        // 송신 스레드가 막혀 헤더가 대기열에 오래 남아 있다. 버릴 수 없는 것이므로 밀린 정도는 영상·소리로 잰다.
        assertTrue(queue.offer(item(MediaQueue.Kind.ESSENTIAL, 40)))
        now = 5_000
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        now = 5_033
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(0, keyRequests)
        now = 7_001
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(1, keyRequests)
    }

    @Test
    fun closeWakesTaker() {
        val queue = MediaQueue(10)
        val taker = Thread { assertNull(queue.take(5000)) }
        taker.start()
        Thread.sleep(50)
        queue.close()
        taker.join(1000)
        assertFalse(taker.isAlive)
        assertFalse(queue.offer(item(MediaQueue.Kind.ESSENTIAL, 1)))
    }
}
