package com.camneuron.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatGovernorTest {
    // PowerManager.THERMAL_STATUS_*
    private val none = 0
    private val moderate = 2
    private val severe = 3
    private val critical = 4
    private val coolBattery = 35f

    @Test
    fun targetLevel_followsThermalStatusAndBatteryTemperature() {
        assertEquals(0, HeatGovernor.targetLevel(1, coolBattery)) // LIGHT
        assertEquals(1, HeatGovernor.targetLevel(moderate, coolBattery))
        assertEquals(2, HeatGovernor.targetLevel(severe, coolBattery))
        assertEquals(3, HeatGovernor.targetLevel(6, coolBattery)) // SHUTDOWN
        assertEquals(2, HeatGovernor.targetLevel(none, 47.5f)) // 발열 단계를 늦게 올리는 폰
        assertEquals(1, HeatGovernor.targetLevel(moderate, 40f))
    }

    @Test
    fun raisesAfterSustainedHeat_andRecoversOneStepAtATime() {
        val heat = HeatGovernor()
        var t = 0L
        assertFalse(heat.update(severe, coolBattery, t))
        t += HeatGovernor.RAISE_MS - 1
        assertFalse(heat.update(severe, coolBattery, t)) // 아직 잠깐 뜨거웠을 뿐
        t += 1
        assertTrue(heat.update(severe, coolBattery, t))
        assertEquals(2, heat.level) // 1단계를 거치지 않고 바로 2단계

        t += 1000
        assertFalse(heat.update(none, coolBattery, t)) // 식기 시작
        t += HeatGovernor.RECOVER_MS
        assertTrue(heat.update(none, coolBattery, t))
        assertEquals(1, heat.level)
        t += HeatGovernor.RECOVER_MS - 1
        assertFalse(heat.update(none, coolBattery, t)) // 다음 단계도 다시 기다린다
        t += 1
        assertTrue(heat.update(none, coolBattery, t))
        assertEquals(0, heat.level)
    }

    @Test
    fun briefCoolingDoesNotRecover() {
        val heat = HeatGovernor()
        heat.update(moderate, coolBattery, 0)
        heat.update(moderate, coolBattery, HeatGovernor.RAISE_MS)
        assertEquals(1, heat.level)

        heat.update(none, coolBattery, 10_000)
        heat.update(moderate, coolBattery, 10_000 + HeatGovernor.RECOVER_MS / 2) // 다시 뜨거워짐 → 대기 초기화
        heat.update(none, coolBattery, 10_000 + HeatGovernor.RECOVER_MS / 2 + 1000)
        assertFalse(heat.update(none, coolBattery, 10_000 + HeatGovernor.RECOVER_MS + 1000))
        assertEquals(1, heat.level)
    }

    @Test
    fun reset_restoresImmediately() {
        val heat = HeatGovernor()
        heat.update(critical, coolBattery, 0)
        heat.update(critical, coolBattery, HeatGovernor.RAISE_MS)
        assertEquals(3, heat.level)
        assertTrue(heat.reset())
        assertEquals(0, heat.level)
        assertFalse(heat.reset())
    }

    @Test
    fun limit_lowersFpsThenResolutionThenBitrate() {
        assertEquals(HeatGovernor.Limits(3840, 2160, 60, 40_000_000), HeatGovernor.limit(3840, 2160, 60, 40_000_000, 0))
        assertEquals(HeatGovernor.Limits(3840, 2160, 30, 32_000_000), HeatGovernor.limit(3840, 2160, 60, 40_000_000, 1))
        assertEquals(HeatGovernor.Limits(1920, 1080, 30, 24_000_000), HeatGovernor.limit(3840, 2160, 60, 40_000_000, 2))
        assertEquals(HeatGovernor.Limits(1280, 720, 30, 16_000_000), HeatGovernor.limit(3840, 2160, 60, 40_000_000, 3))
        // 이미 낮은 값은 올리지 않고, 비트레이트는 2 Mbps 밑으로 내리지 않는다 (원래 더 낮으면 그대로)
        assertEquals(HeatGovernor.Limits(1280, 720, 24, 2_000_000), HeatGovernor.limit(1280, 720, 24, 3_000_000, 3))
        assertEquals(1_500_000, HeatGovernor.limitBitrate(1_500_000, 3))
    }
}
