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
