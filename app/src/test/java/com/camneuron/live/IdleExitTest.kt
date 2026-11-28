package com.camneuron.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleExitTest {
    @Test
    fun exitsOnlyWhenIdleAndOffScreen() {
        assertTrue(IdleExit.shouldExit(startedActivities = 0, phase = LiveState.Phase.IDLE))
        assertFalse(IdleExit.shouldExit(startedActivities = 1, phase = LiveState.Phase.IDLE))
    }

    @Test
    fun neverExitsWhileBroadcasting() {
        for (phase in listOf(LiveState.Phase.CONNECTING, LiveState.Phase.LIVE, LiveState.Phase.RECONNECTING)) {
            assertFalse(IdleExit.shouldExit(startedActivities = 0, phase = phase))
        }
    }

    @Test
    fun waitsTenMinutes() {
        assertEquals(600_000L, IdleExit.IDLE_EXIT_MS)
    }
}
