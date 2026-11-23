package com.camneuron.live.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaQueueTest {
    private fun item(kind: MediaQueue.Kind, size: Int, ts: Long = 0) =
        MediaQueue.Item(kind, ChunkWriter.CSID_VIDEO, RtmpMessage.VIDEO, ts, ByteArray(size))

    @Test
    fun overflow_dropsQueuedVideoAndWaitsForKeyFrame() {
        var keyRequests = 0
        val queue = MediaQueue(100) { keyRequests++ }
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 40)))
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 40)))
        assertTrue(queue.offer(item(MediaQueue.Kind.AUDIO, 10)))
        // 넘친다: 쌓인 영상 둘과 이 프레임을 버리고 키프레임을 요청한다. 소리는 남는다.
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 40)))
        assertEquals(1, keyRequests)
        assertEquals(3, queue.droppedFrames)
        assertEquals(10, queue.queuedBytes())
        // 키프레임 전의 중간 프레임은 자리가 있어도 버린다.
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 5)))
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 50)))
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 5)))

        assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
        assertEquals(MediaQueue.Kind.KEY_FRAME, queue.take(0)!!.kind)
        assertEquals(MediaQueue.Kind.FRAME, queue.take(0)!!.kind)
        assertNull(queue.take(0))

        val stats = queue.takeStats()
        assertEquals(1, stats.drops)
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
    fun audioIsDroppedOnlyWhenNoVideoToDrop() {
        var keyRequests = 0
        val queue = MediaQueue(20) { keyRequests++ }
        assertTrue(queue.offer(item(MediaQueue.Kind.AUDIO, 15)))
        assertFalse(queue.offer(item(MediaQueue.Kind.AUDIO, 10)))
        assertEquals(0, keyRequests)
        assertEquals(1, queue.takeStats().droppedAudio)
    }

    @Test
    fun oversizedKeyFrameIsStillQueued() {
        val queue = MediaQueue(10)
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 30)))
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 30))) // 앞의 것을 버리고 넣는다
        assertEquals(30, queue.queuedBytes())
    }

    @Test
    fun waitingTooLong_dropsQueuedVideoEvenWithRoomLeft() {
        var now = 0L
        var keyRequests = 0
        val queue = MediaQueue(1_000_000, maxDelayMs = 2_000, clockMs = { now }) { keyRequests++ }
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        now = 1_000
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        now = 2_000
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100))) // 딱 2초: 아직 괜찮다
        assertEquals(0, keyRequests)

        // 맨 앞이 2초 넘게 기다렸다 (업로드가 느리다). 자리는 넉넉해도 쌓인 영상을 버리고 키프레임부터 다시.
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
        assertEquals(0.2f, queue.takeStats().fill, 0.001f)
    }

    @Test
    fun audioDoesNotWaitBehindStaleVideo() {
        var now = 0L
        var keyRequests = 0
        val queue = MediaQueue(1_000_000, maxDelayMs = 2_000, clockMs = { now }) { keyRequests++ }
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        now = 2_500
        // 소리가 오면 밀린 영상을 버리고 소리는 넣는다. 다음 영상은 키프레임부터.
        assertTrue(queue.offer(item(MediaQueue.Kind.AUDIO, 10)))
        assertEquals(1, keyRequests)
        assertEquals(10, queue.queuedBytes())
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
    }

    private fun audio(ts: Long, size: Int = 10) =
        MediaQueue.Item(MediaQueue.Kind.AUDIO, ChunkWriter.CSID_AUDIO, RtmpMessage.AUDIO, ts, ByteArray(size))

    private fun limited(clock: () -> Long, onNeedKeyFrame: () -> Unit = {}) =
        MediaQueue(1_000_000, maxDelayMs = 2_000, clockMs = clock, maxAudioDelayMs = 3_000, onNeedKeyFrame = onNeedKeyFrame)

    @Test
    fun soundWaitingTooLong_isSkippedAndTheTimelineContinuesWithoutAGap() {
        var now = 0L
        var keyRequests = 0
        val queue = limited({ now }) { keyRequests++ }
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100, ts = 0)))
        assertTrue(queue.offer(audio(0)))
        assertEquals(0L, queue.take(0)!!.sendTimestampMs)
        assertEquals(0L, queue.take(0)!!.sendTimestampMs)

        // 업로드가 멈췄다: 소리만 쌓인다. 3초까지는 둔다.
        now = 100
        assertTrue(queue.offer(audio(100)))
        now = 1_100
        assertTrue(queue.offer(audio(1_100)))
        now = 3_100
        assertTrue(queue.offer(audio(3_100)))
        assertEquals(30, queue.queuedBytes())
        assertEquals(0L, queue.takeStats().skippedMs)

        // 맨 앞 소리가 3초 넘게 기다렸다: 쌓인 소리를 버리고, 이번 것을 방금 보낸 것 바로 뒤에 잇는다.
        now = 3_101
        val next = audio(3_101)
        assertTrue(queue.offer(next))
        assertEquals(10, queue.queuedBytes())
        assertEquals(20L, next.sendTimestampMs)
        val stats = queue.takeStats()
        assertEquals(3_081L, stats.skippedMs)
        assertEquals(3, stats.droppedAudio)
        assertEquals(1, stats.drops)
        assertEquals(1, keyRequests) // 영상은 키프레임부터 다시

        // 그 뒤로는 영상도 소리도 같은 만큼 당겨진다 (둘이 어긋나지 않는다).
        val later = audio(3_122)
        val key = item(MediaQueue.Kind.KEY_FRAME, 100, ts = 3_130)
        assertTrue(queue.offer(later))
        assertTrue(queue.offer(key))
        assertEquals(41L, later.sendTimestampMs)
        assertEquals(49L, key.sendTimestampMs)
        assertEquals(3_130L, key.timestampMs)
    }

    @Test
    fun skipKeepsSequenceHeadersAndNeverSendsTimeBackwards() {
        var now = 0L
        val queue = limited({ now })
        assertTrue(queue.offer(audio(0)))
        assertEquals(0L, queue.take(0)!!.sendTimestampMs)
        now = 500
        assertTrue(queue.offer(audio(500)))
        now = 2_000
        val header = item(MediaQueue.Kind.ESSENTIAL, 40, ts = 2_000) // 카메라를 바꿔 새로 온 시퀀스 헤더
        assertTrue(queue.offer(header))
        assertEquals(2_000L, header.sendTimestampMs)

        now = 3_600
        val next = audio(3_600)
        assertTrue(queue.offer(next))
        // 헤더는 남고, 건너뛴 자리로 당겨진다. 그 뒤 것들은 그보다 이르지 않다.
        assertEquals(50, queue.queuedBytes())
        assertEquals(20L, header.sendTimestampMs)
        assertEquals(20L, next.sendTimestampMs)
        assertEquals(MediaQueue.Kind.ESSENTIAL, queue.take(0)!!.kind)
        val key = item(MediaQueue.Kind.KEY_FRAME, 100, ts = 3_610)
        assertTrue(queue.offer(key))
        assertEquals(30L, key.sendTimestampMs)
        assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
        assertEquals(MediaQueue.Kind.KEY_FRAME, queue.take(0)!!.kind)
    }

    @Test
    fun videoRestartsOnlyAfterQueuedSoundHasGone() {
        var now = 0L
        var keyRequests = 0
        val queue = limited({ now }) { keyRequests++ }
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertTrue(queue.offer(audio(0)))
        assertEquals(MediaQueue.Kind.KEY_FRAME, queue.take(0)!!.kind) // 송신 스레드가 이것을 보내느라 막혀 있다

        // 2초 넘게 밀렸다: 영상을 버린다. 소리가 아직 2초 밀려 있으므로 키프레임은 지금 달라고 하지 않는다.
        now = 2_001
        assertTrue(queue.offer(audio(2_001)))
        assertEquals(20, queue.queuedBytes())
        assertEquals(0, keyRequests)
        // 그 사이에 온 키프레임(2초마다 오는 것)도 넣지 않는다: 느린 망에서 소리가 그 뒤로 더 밀린다.
        now = 2_010
        assertFalse(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(0, keyRequests)

        // 밀린 소리가 나가면 그때 키프레임을 달라고 하고, 온 것을 넣는다.
        assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
        assertEquals(1, keyRequests)
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
        assertEquals(1, keyRequests)
    }

    @Test
    fun whenRestartedVideoChokesTheLink_videoRestsLongerEachTime() {
        var now = 0L
        var keyRequests = 0
        val queue = limited({ now }) { keyRequests++ }

        /**
         * 영상을 다시 시작하고(키프레임이 들어가 송신 스레드가 붙잡고 있다) 3초 뒤 소리까지 건너뛴다.
         * 그 뒤에 온 소리는 바로 나간다. 건너뛴 시각을 돌려준다.
         */
        fun restartThenChoke(): Long {
            assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
            assertEquals(MediaQueue.Kind.KEY_FRAME, queue.take(0)!!.kind)
            now += 10
            assertTrue(queue.offer(audio(now)))
            now += 3_001
            assertTrue(queue.offer(audio(now)))
            assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
            return now
        }

        // 처음: 영상이 밀려 버려진 뒤 다시 시작한다.
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(MediaQueue.Kind.KEY_FRAME, queue.take(0)!!.kind)
        now = 2_001
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(1, keyRequests)

        // 다시 시작하자마자 소리까지 건너뛰었다: 5초 동안 영상을 쉰다 (소리만 보낸다).
        var skippedAt = restartThenChoke()
        assertEquals(1, keyRequests)
        // 쉬는 동안에는 대기열이 비어 있어도 망이 좋아진 것으로 알리지 않는다 (비트레이트를 도로 올리지 않게).
        queue.takeStats()
        now = skippedAt + 2_000
        assertEquals(0.3f, queue.takeStats().fill, 0.001f)
        now = skippedAt + 4_999
        assertFalse(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertTrue(queue.offer(audio(now)))
        assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
        assertEquals(1, keyRequests)
        now = skippedAt + 5_000
        assertTrue(queue.offer(audio(now)))
        assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
        assertEquals(2, keyRequests) // 쉬는 시간이 끝났다

        // 또 그랬다: 이번에는 10초.
        skippedAt = restartThenChoke()
        now = skippedAt + 9_999
        assertFalse(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertTrue(queue.offer(audio(now)))
        assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
        assertEquals(2, keyRequests)
        now = skippedAt + 10_000
        assertTrue(queue.offer(audio(now)))
        assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
        assertEquals(3, keyRequests)

        // 이번에는 버텼다 (6초 넘게 건너뛰지 않았다). 다음에 영상이 밀려도 쉬지 않고 바로 다시 시작한다.
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertEquals(MediaQueue.Kind.KEY_FRAME, queue.take(0)!!.kind)
        now += 60_000
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        now += 2_001
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(4, keyRequests)
        assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
        assertEquals(MediaQueue.Kind.KEY_FRAME, queue.take(0)!!.kind)
        skippedAt = restartThenChoke().also { /* 다시 처음부터: 5초 */ }
        now = skippedAt + 5_000
        assertTrue(queue.offer(audio(now)))
        assertEquals(MediaQueue.Kind.AUDIO, queue.take(0)!!.kind)
        assertEquals(5, keyRequests)
    }

    @Test
    fun afterARestartFailsQuickly_theNextTryDropsVideoSooner() {
        var now = 0L
        var keyRequests = 0
        val queue = limited({ now }) { keyRequests++ }

        /** 영상을 다시 시작한다: 키프레임은 송신 스레드가 가져가고, 그 뒤 프레임 하나가 대기열에서 기다린다. */
        fun restart() {
            now += 10
            assertTrue(queue.offer(item(MediaQueue.Kind.KEY_FRAME, 100)))
            assertEquals(MediaQueue.Kind.KEY_FRAME, queue.take(0)!!.kind)
            assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        }

        // 평소: 2초 넘게 밀려야 버린다.
        restart()
        now += 2_000
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        now += 1
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(1, keyRequests)

        // 다시 시작했는데 6초도 못 가 또 밀렸다: 망이 영상을 못 싣는다.
        restart()
        now += 2_001
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(2, keyRequests)

        // 그 다음 시도는 1초만 밀려도 버린다 (소리가 그 뒤에서 덜 기다린다).
        restart()
        now += 1_000
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        now += 1
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(3, keyRequests)

        // 이번에는 6초 넘게 버텼다: 다시 2초까지 기다려 준다. 그 뒤로도.
        restart()
        assertEquals(MediaQueue.Kind.FRAME, queue.take(0)!!.kind)
        now += 7_000
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        now += 1_500
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        now += 501
        assertFalse(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
        assertEquals(4, keyRequests)
        restart()
        now += 1_500
        assertTrue(queue.offer(item(MediaQueue.Kind.FRAME, 100)))
    }

    @Test
    fun withoutSoundLimit_soundIsKeptAsBefore() {
        var now = 0L
        val queue = MediaQueue(1_000_000, maxDelayMs = 2_000, clockMs = { now })
        assertTrue(queue.offer(audio(0)))
        now = 60_000
        val late = audio(60_000)
        assertTrue(queue.offer(late))
        assertEquals(20, queue.queuedBytes())
        assertEquals(60_000L, late.sendTimestampMs)
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
