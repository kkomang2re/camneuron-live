package com.camneuron.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadGovernorTest {
    private var now = 0L
    private val g = UploadGovernor(maxLevel = 6, recoverMs = 8_000)

    /** 4.5 Mbps 방송의 단계별 양 (소리 포함): 4.67, 3.32, 2.42, 1.745, 1.16, 0.80, 0.575 Mbps. */
    private val bitrateAt = { level: Int -> LiveNet.totalBitrate(4_500_000, level) }

    /** 0.125초 뒤에 본 것: 대기열 맨 앞이 밀린 시간과, 방금 잰 망 속도 (재지 못했으면 음수). */
    private fun check(delay: Long, linkKbps: Int): Boolean {
        now += 125
        return g.onDelay(delay, if (linkKbps < 0) -1 else linkKbps * 1000, now, bitrateAt)
    }

    /** 1초 뒤의 틱. */
    private fun tick(skips: Int = 0, fill: Float = 0f): Boolean {
        now += 1_000
        return g.update(skips, fill, now)
    }

    @Test
    fun aBacklogJumpsStraightToALevelTheLinkCanCarry() {
        assertFalse(check(100, -1))    // 밀리기 시작했지만 아직 망 속도를 재지 못했다
        assertFalse(check(250, 1_500)) // 쟀지만 아직 0.3초가 안 됐다
        assertTrue(check(320, 1_500))
        assertEquals(4, g.level) // 1.5 Mbps의 85%에 드는 단계: 1.16 Mbps
        // 내린 뒤에도 먼저 쌓인 것 때문에 밀린 시간은 한동안 더 늘어 보인다. 잰 속도가 그대로면 더 내리지 않는다.
        for (ms in listOf(500L, 900L, 1_100L, 700L, 300L, 0L)) assertFalse(check(ms, 1_500))
        assertEquals(4, g.level)
    }

    @Test
    fun aMilderSlowdownGoesDownLess() {
        // 3 Mbps: 그 85%(2.55 Mbps)에 드는 2단계(2.42 Mbps).
        assertTrue(check(350, 3_000))
        assertEquals(2, g.level)
    }

    @Test
    fun withoutAMeasuredSpeedItWaits() {
        // 아예 막혀 있으면 잴 수 없다. 잠깐 끊긴 것일 수 있으니 여기서는 내리지 않는다 (정말 끊긴 것이면 건너뛸 때 내려간다).
        for (ms in 300L..1_900L step 100) assertFalse(check(ms, -1))
        assertEquals(0, g.level)
    }

    @Test
    fun aLinkOnlyAsFastAsTheStreamGoesDownOneLevel() {
        // 보내는 만큼(4.67 Mbps)은 나가지만 밀려 있다: 여유가 없으니 한 단계 내린다.
        assertTrue(check(400, 4_670))
        assertEquals(1, g.level)
    }

    @Test
    fun aLinkFasterThanTheStreamIsLeftAlone() {
        // 잠깐 막혔다가 풀려 쌓인 것이 빠르게 빠지는 중.
        for (ms in listOf(450L, 350L, 200L, 0L)) assertFalse(check(ms, 8_000))
        assertEquals(0, g.level)
    }

    @Test
    fun aLowerMeasurementLaterGoesFurther() {
        assertTrue(check(320, 1_500))
        assertEquals(4, g.level)
        // 망이 더 느려졌다 (0.6 Mbps: 가장 낮은 단계도 빠듯하다).
        assertTrue(check(600, 600))
        assertEquals(6, g.level)
        assertFalse(check(900, 300)) // 더 내려갈 데가 없다
    }

    @Test
    fun aSkipGoesDownTwoLevels() {
        assertTrue(tick(skips = 1, fill = 1f))
        assertEquals(2, g.level)
        assertTrue(tick(skips = 1, fill = 1f))
        assertEquals(4, g.level)
        assertTrue(tick(skips = 2, fill = 1f))
        assertEquals(6, g.level)
        assertFalse(tick(skips = 1, fill = 1f)) // 더 내려갈 데가 없다
        assertEquals(6, g.level)
    }

    @Test
    fun rightAfterASkipTheMeasuredSpeedIsNotTrusted() {
        assertTrue(tick(skips = 1, fill = 1f))
        assertEquals(2, g.level)
        // 건너뛴 직후 2초 동안은 소켓에 남은 옛것이 빠지느라 쓰기가 막힌다. 그때 잰 것으로 내리지 않는다.
        repeat(15) { assertFalse(check(1_000, 100)) }
        assertEquals(2, g.level)
        assertTrue(check(1_000, 100))
        assertEquals(6, g.level)
    }

    @Test
    fun aQueueThatStaysFullGoesDownOneLevelEveryThreeSeconds() {
        // 잰 망 속도로는 지금 단계가 맞는데도 계속 차 있다 (인코더가 정한 것보다 많이 내보낸다).
        assertFalse(tick(fill = 0.3f))
        assertFalse(tick(fill = 0.5f)) // 찬 지 1초
        assertFalse(tick(fill = 0.6f))
        assertTrue(tick(fill = 0.6f))  // 3초째: 한 단계
        assertEquals(1, g.level)
        assertFalse(tick(fill = 0.7f))
        assertFalse(tick(fill = 0.7f))
        assertTrue(tick(fill = 0.7f))
        assertEquals(2, g.level)
        // 밀린 것이 빠지기 시작했다: 지금 단계로 충분하다.
        assertFalse(tick(fill = 0.55f))
        assertFalse(tick(fill = 0.4f))
        assertFalse(tick(fill = 0.1f))
        assertEquals(2, g.level)
    }

    @Test
    fun halfFullIsNeitherGoodNorSlow() {
        tick(skips = 1, fill = 1f)
        repeat(30) { assertFalse(tick(fill = 0.3f)) }
        assertEquals(2, g.level)
    }

    @Test
    fun recoversOneLevelAtATime() {
        repeat(3) { tick(skips = 1, fill = 1f) }
        assertEquals(6, g.level)
        val changes = ArrayList<Long>()
        val start = now
        while (g.level > 0 && now - start < 120_000) if (tick()) changes += now - start
        assertEquals(listOf(9_000L, 17_000L, 25_000L, 33_000L, 41_000L, 49_000L), changes)
        assertEquals(0, g.level)
    }

    @Test
    fun fallingBackToTheLevelBeforeTheRaiseDoublesTheNextWait() {
        tick(skips = 1, fill = 1f)
        assertEquals(2, g.level)
        repeat(9) { tick() } // 8초 좋으면 한 단계 올린다
        assertEquals(1, g.level)
        // 올린 단계(3.32 Mbps)는 넘치고 올리기 전 단계(2.42 Mbps)는 실린다: 망이 3 Mbps쯤이다.
        assertTrue(check(400, 3_000))
        assertEquals(2, g.level)
        val start = now
        while (g.level == 2) tick()
        assertEquals(17_000L, now - start) // 다음에는 16초를 기다린다 (첫 틱에 세기 시작한다)
    }

    @Test
    fun fallingFurtherThanBeforeTheRaiseIsNotBlamedOnTheRaise() {
        tick(skips = 1, fill = 1f)
        repeat(9) { tick() }
        assertEquals(1, g.level)
        // 올린 직후에 망이 훨씬 나빠졌다 (걷다가 느린 곳에 들어섰다). 올린 탓이 아니므로 다음에도 8초만 기다린다.
        assertTrue(check(400, 1_500))
        assertEquals(4, g.level)
        val start = now
        while (g.level == 4) tick()
        assertEquals(9_000L, now - start)
    }

    @Test
    fun aLongPauseStartsOver() {
        repeat(2) { tick(skips = 1, fill = 1f) }
        assertEquals(4, g.level)
        now += 61_000
        assertFalse(g.update(0, 0f, now))
        assertEquals(0, g.level)
    }
}
