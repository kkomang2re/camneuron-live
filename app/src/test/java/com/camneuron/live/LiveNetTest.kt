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
}
