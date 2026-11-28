package com.camneuron.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNetTest {
    @Test
    fun bitrateLadderGoesDownToAboutATenth() {
        val levels = (0..LiveNet.MAX_LEVEL).map { LiveNet.limitBitrate(4_500_000, it) }
        assertEquals(listOf(4_500_000, 3_150_000, 2_250_000, 1_575_000, 990_000, 630_000, 405_000), levels)
        // 단계마다 낮아지고, 가장 낮은 단계는 소리와 합쳐 600 kbps 업로드에 들어간다.
        assertTrue(levels.zipWithNext().all { (a, b) -> b < a })
        assertTrue(levels.last() + 160_000 < 600_000)
        assertEquals(levels.last(), LiveNet.limitBitrate(4_500_000, 99))
    }

    @Test
    fun bitrateIsNotLoweredBelowTheFloor() {
        assertEquals(550_000, LiveNet.limitBitrate(2_500_000, 4))
        assertEquals(LiveNet.MIN_BITRATE, LiveNet.limitBitrate(2_500_000, 5))
        assertEquals(LiveNet.MIN_BITRATE, LiveNet.limitBitrate(2_500_000, 6))
        // 원래 바닥보다 낮게 잡은 비트레이트는 그대로 둔다.
        assertEquals(300_000, LiveNet.limitBitrate(300_000, 6))
        assertEquals(8_000_000, LiveNet.limitBitrate(8_000_000, 0))
    }

    @Test
    fun governorGoesDownToTheLastLevel() {
        val g = LiveNet.governor()
        var now = 0L
        repeat(5) {
            now += 1_000
            g.update(skips = 1, queueFill = 1f, nowMs = now)
        }
        assertEquals(LiveNet.MAX_LEVEL, g.level)
    }

    @Test
    fun totalBitrateAddsTheSound() {
        assertEquals(4_670_000, LiveNet.totalBitrate(4_500_000, 0))
        assertEquals(575_000, LiveNet.totalBitrate(4_500_000, 6))
    }

    @Test
    fun sendBufferGrowsAtOnce() {
        // 비트레이트를 올렸다: 대기열이 밀려 있어도 바로 늘린다.
        assertEquals(72_968, LiveNet.sendBuffer(currentBytes = 20_000, totalBps = 4_670_000, queueDelayMs = 1_500, skipped = false))
        assertEquals(72_968, LiveNet.sendBuffer(currentBytes = 72_968, totalBps = 4_670_000, queueDelayMs = 0, skipped = false))
    }

    @Test
    fun sendBufferShrinksOnlyWhileTheQueueIsEmptyOrRightAfterASkip() {
        // 비트레이트를 낮췄지만 밀려 있다: 지금 줄이면 쓰기가 막혀 더 밀린다.
        assertEquals(72_968, LiveNet.sendBuffer(72_968, totalBps = 1_160_000, queueDelayMs = 1_500, skipped = false))
        assertEquals(72_968, LiveNet.sendBuffer(72_968, totalBps = 1_160_000, queueDelayMs = LiveNet.CALM_MS + 1, skipped = false))
        // 밀린 것이 다 나갔다: 줄인다.
        assertEquals(18_125, LiveNet.sendBuffer(72_968, totalBps = 1_160_000, queueDelayMs = LiveNet.CALM_MS, skipped = false))
        // 방금 건너뛰었다: 소켓에 남은 옛것부터 빠져야 하므로 줄인다.
        assertEquals(18_125, LiveNet.sendBuffer(72_968, totalBps = 1_160_000, queueDelayMs = 2_000, skipped = true))
        // 가장 낮은 단계에서는 최소 크기.
        assertEquals(16 * 1024, LiveNet.sendBuffer(18_125, totalBps = 575_000, queueDelayMs = 0, skipped = false))
    }
}
