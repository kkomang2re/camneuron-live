package com.camneuron.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.camneuron.core.device.HeatGovernor

class NetGovernorTest {
    @Test
    fun dropsLowerOneLevelAtATime() {
        val g = NetGovernor()
        assertTrue(g.update(drops = 1, queueFill = 1f, nowMs = 0))
        assertEquals(1, g.level)
        // 바로 다음 초에도 버렸지만 2초 간격을 지킨다
        assertFalse(g.update(drops = 1, queueFill = 1f, nowMs = 1_000))
        assertTrue(g.update(drops = 1, queueFill = 1f, nowMs = 2_000))
        assertEquals(2, g.level)
    }

    @Test
    fun slowQueueLowersAfterTwoSeconds() {
        val g = NetGovernor()
        assertFalse(g.update(drops = 0, queueFill = 0.6f, nowMs = 0))
        assertTrue(g.update(drops = 0, queueFill = 0.6f, nowMs = 1_000))
        assertEquals(1, g.level)
    }

    @Test
    fun recoversSlowlyAndWaitsLongerFrom720p() {
        val g = NetGovernor()
        var now = 0L
        repeat(NetGovernor.MAX_LEVEL) {
            g.update(drops = 1, queueFill = 1f, nowMs = now)
            now += NetGovernor.DOWN_INTERVAL_MS
        }
        assertEquals(NetGovernor.MAX_LEVEL, g.level)
        // 720p에서 돌아오기: 30초
        g.update(0, 0f, now)
        assertFalse(g.update(0, 0f, now + NetGovernor.RECOVER_MS))
        assertTrue(g.update(0, 0f, now + NetGovernor.RECOVER_RESIZE_MS))
        assertEquals(3, g.level)
        // 그다음은 15초마다
        now += NetGovernor.RECOVER_RESIZE_MS
        assertFalse(g.update(0, 0f, now + 14_000))
        assertTrue(g.update(0, 0f, now + NetGovernor.RECOVER_MS))
        assertEquals(2, g.level)
    }

    @Test
    fun failedStepUpWaitsLongerNextTime() {
        val g = NetGovernor()
        g.update(drops = 1, queueFill = 1f, nowMs = 0)
        g.update(drops = 1, queueFill = 1f, nowMs = 2_000) // 2단계
        // 15초 좋으면 1단계로 올림
        g.update(0, 0f, 3_000)
        assertTrue(g.update(0, 0f, 18_000))
        assertEquals(1, g.level)
        // 올리자마자 다시 밀림: 2단계로, 다음엔 30초를 기다린다
        assertTrue(g.update(drops = 1, queueFill = 1f, nowMs = 20_000))
        assertEquals(2, g.level)
        g.update(0, 0f, 21_000)
        assertFalse(g.update(0, 0f, 21_000 + NetGovernor.RECOVER_MS))
        assertTrue(g.update(0, 0f, 21_000 + 2 * NetGovernor.RECOVER_MS))
        assertEquals(1, g.level)
    }

    @Test
    fun halfFullQueueDoesNotRecover() {
        val g = NetGovernor()
        g.update(drops = 1, queueFill = 1f, nowMs = 0)
        for (s in 1..40) assertFalse(g.update(drops = 0, queueFill = 0.3f, nowMs = s * 1_000L))
        assertEquals(1, g.level)
    }

    @Test
    fun staleLevelIsForgotten() {
        val g = NetGovernor()
        g.update(drops = 1, queueFill = 1f, nowMs = 0)
        g.update(drops = 0, queueFill = 0f, nowMs = NetGovernor.STALE_MS + 1)
        assertEquals(0, g.level)
    }

    @Test
    fun limits() {
        val full = HeatGovernor.Limits(1920, 1080, 60, 12_000_000)
        assertEquals(full, NetGovernor.limit(full, 0))
        assertEquals(HeatGovernor.Limits(1920, 1080, 60, 6_000_000), NetGovernor.limit(full, 2))
        assertEquals(HeatGovernor.Limits(1280, 720, 60, 4_200_000), NetGovernor.limit(full, 4))
        // 이미 낮은 비트레이트는 1.5 Mbps 밑으로 내리지 않고, 원래보다 올리지도 않는다
        assertEquals(1_500_000, NetGovernor.limitBitrate(3_000_000, 3))
        assertEquals(1_000_000, NetGovernor.limitBitrate(1_000_000, 3))
    }
}
