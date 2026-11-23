package com.camneuron.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNetTest {
    @Test
    fun governorStopsAtLevel3AndRecoversEvery8Seconds() {
        val g = LiveNet.governor()
        var now = 0L
        repeat(6) {
            g.update(drops = 1, queueFill = 1f, nowMs = now)
            now += 2_000
        }
        assertEquals(3, g.level)
        g.update(0, 0f, now)
        assertTrue(g.update(0, 0f, now + LiveNet.RECOVER_MS))
        assertEquals(2, g.level)
    }

    @Test
    fun sendBufferFollowsTheBitrateWhileUploadKeepsUp() {
        val buffer = LiveNet.SendBuffer()
        assertEquals(140_625, buffer.update(4_500_000, 560_000, 0))
        assertEquals(49_218, buffer.update(1_575_000, 200_000, 900))
        assertEquals(49_218, buffer.update(1_575_000, -1, 5_000)) // 보낸 양을 아직 재지 못했다
    }

    @Test
    fun sendBufferShrinksToWhatActuallyGetsOutWhileBacklogged() {
        val buffer = LiveNet.SendBuffer()
        // 300 kbps만 나간다: 0.75초 분량.
        assertEquals(28_125, buffer.update(1_575_000, 37_500, 2_000))
        // 완전히 막혔다: 최소 크기.
        assertEquals(LiveNet.SendBuffer.MIN_BYTES, buffer.update(1_575_000, 0, 3_000))
        // 넉넉히 나가고 있으면 비트레이트 기준 그대로.
        assertEquals(49_218, buffer.update(1_575_000, 150_000, 1_500))
    }

    @Test
    fun sendBufferReturnsWhenUploadSpeedsUpOrAfterThirtyClearSeconds() {
        val slow = LiveNet.SendBuffer()
        assertEquals(28_125, slow.update(1_575_000, 37_500, 2_000))
        // 밀림은 없어졌지만 소리만 보내고 있어 망이 빨라졌는지 모른다: 한동안은 줄인 채로 둔다.
        repeat(LiveNet.SendBuffer.FORGET_SECONDS - 1) { assertEquals(28_125, slow.update(1_575_000, 21_000, 0)) }
        assertEquals(49_218, slow.update(1_575_000, 21_000, 0))

        val fast = LiveNet.SendBuffer()
        assertEquals(28_125, fast.update(1_575_000, 37_500, 2_000))
        // 많이 나가기 시작했다: 바로 돌아온다.
        assertEquals(49_218, fast.update(1_575_000, 220_000, 300))
    }
}
